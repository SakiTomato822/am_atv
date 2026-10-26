package dev.amatv.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class MusicKitStackMapFramesTest {
    @Test
    public void selectsMissingFramesButNotLinearLegacyOrAlreadyFramedClasses() {
        byte[] missing = fixture("sdk/Fixture", Opcodes.V1_8, true);
        assertTrue(MusicKitStackMapFrames.needsFrameComputation(missing));
        assertFalse(MusicKitStackMapFrames.needsFrameComputation(fixture("sdk/Linear", Opcodes.V1_8, false)));
        assertFalse(MusicKitStackMapFrames.needsFrameComputation(fixture("sdk/Legacy", Opcodes.V1_6, true)));
        assertFalse(MusicKitStackMapFrames.needsFrameComputation(recompute(missing)));
    }

    @Test
    public void frameRecomputationPreservesInstructionsTargetsAndBothBranchResults() throws Exception {
        byte[] original = fixture("sdk/Fixture", Opcodes.V1_8, true);
        byte[] repaired = recompute(original);
        assertEquals(instructions(original), instructions(repaired));
        assertTrue(hasFrames(repaired));
        try {
            new FixtureLoader().define(original).getMethod("choose", boolean.class);
            fail("A Java 8 branch without its required stack-map frame must not verify on the JVM");
        } catch (VerifyError expected) {
            // This is the missing metadata that R8 warns about, not altered behavior.
        }
        Class<?> verified = new FixtureLoader().define(repaired);
        assertEquals("yes", verified.getMethod("choose", boolean.class).invoke(null, true));
        assertEquals("no", verified.getMethod("choose", boolean.class).invoke(null, false));
    }

    @Test
    public void exactAarContentsDefineTheAllowlistInsteadOfBroadPackagePrefixes() throws IOException {
        byte[] missing = fixture("vendor/private/NeedsFrames", Opcodes.V1_8, true);
        byte[] valid = recompute(fixture("vendor/private/AlreadyFramed", Opcodes.V1_8, true));
        Path aar = Files.createTempFile("amatv-sdk-frame-fixture-", ".aar");
        try {
            ByteArrayOutputStream classes = new ByteArrayOutputStream();
            try (ZipOutputStream jar = new ZipOutputStream(classes)) {
                entry(jar, "vendor/private/NeedsFrames.class", missing);
                entry(jar, "vendor/private/AlreadyFramed.class", valid);
                entry(jar, "vendor/private/Linear.class", fixture("vendor/private/Linear", Opcodes.V1_8, false));
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aar))) {
                entry(zip, "classes.jar", classes.toByteArray());
            }
            assertEquals(Set.of("vendor.private.NeedsFrames"),
                    MusicKitStackMapFrames.missingFrameClasses(List.of(aar.toFile())));
        } finally {
            Files.deleteIfExists(aar);
        }
    }

    @Test
    public void actualOfficialSdkSelectsEmbeddedDependenciesWithoutIncludingTheApp() throws IOException {
        Path playback = Path.of(System.getProperty("amatv.musicKitSdkAar"));
        Path auth = playback.resolveSibling("musickitauth-release-1.1.2.aar");
        Assume.assumeTrue("Download official SDKs for the artifact coverage check",
                Files.isRegularFile(playback) && Files.isRegularFile(auth));
        MusicKitSongIdPatch.verifySdk(playback);
        Set<String> selected = MusicKitStackMapFrames.missingFrameClasses(List.of(playback.toFile(), auth.toFile()));
        assertTrue(selected.contains(MusicKitSongIdPatch.CLASS_NAME));
        assertTrue(selected.stream().anyMatch(name -> name.startsWith("com.a.a.")));
        assertTrue(selected.stream().anyMatch(name -> name.startsWith("org.bytedeco.javacpp.")));
        assertTrue(selected.stream().anyMatch(name -> name.startsWith("com.apple.android.sdk.authentication.")));
        assertFalse(selected.stream().anyMatch(name -> name.startsWith("dev.amatv.") || name.startsWith("org.mozilla.")));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] fixture(String name, int version, boolean branch) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(Z)Ljava/lang/String;", null, null);
        method.visitCode();
        if (branch) {
            Label alternative = new Label();
            method.visitVarInsn(Opcodes.ILOAD, 0);
            method.visitJumpInsn(Opcodes.IFEQ, alternative);
            method.visitLdcInsn("yes");
            method.visitInsn(Opcodes.ARETURN);
            method.visitLabel(alternative);
        }
        method.visitLdcInsn("no");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] recompute(byte[] original) {
        // Match AGP's class-level mode: read/replay without old frame metadata,
        // then let the writer compute frames and maxima for the complete class.
        ClassNode owner = read(original, ClassReader.SKIP_FRAMES);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes, int flags) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, flags);
        return owner;
    }

    private static boolean hasFrames(byte[] bytes) {
        for (MethodNode method : read(bytes, 0).methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FrameNode) return true;
            }
        }
        return false;
    }

    private static List<String> instructions(byte[] bytes) {
        List<String> instructions = new ArrayList<>();
        for (MethodNode method : read(bytes, 0).methods) {
            Map<LabelNode, Integer> targets = new IdentityHashMap<>();
            int offset = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LabelNode label) targets.put(label, offset);
                if (instruction.getOpcode() >= 0) offset++;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                String operand = "";
                if (instruction instanceof JumpInsnNode jump) operand = ":" + targets.get(jump.label);
                if (instruction instanceof LdcInsnNode constant) operand = ":" + constant.cst;
                if (instruction instanceof VarInsnNode variable) operand = ":" + variable.var;
                instructions.add(instruction.getOpcode() + operand);
            }
        }
        return instructions;
    }

    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
