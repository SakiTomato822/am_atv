package dev.amatv.build;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

/** Selects missing verification metadata only from classes actually shipped in the supplied SDK AARs. */
public final class MusicKitStackMapFrames {
    private MusicKitStackMapFrames() {}

    public static Set<String> missingFrameClasses(Collection<File> sdkAars) throws IOException {
        Set<String> selected = new LinkedHashSet<>();
        for (File aarFile : sdkAars) {
            try (ZipFile aar = new ZipFile(aarFile)) {
                var classes = aar.getEntry("classes.jar");
                if (classes == null) {
                    throw new IOException("MusicKit SDK AAR has no classes.jar: " + aarFile.getName());
                }
                try (ZipInputStream jar = new ZipInputStream(aar.getInputStream(classes))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = jar.getNextEntry()) != null) {
                        if (!entry.getName().endsWith(".class")) continue;
                        byte[] bytes = jar.readAllBytes();
                        if (needsFrameComputation(bytes)) {
                            selected.add(new ClassReader(bytes).getClassName().replace('/', '.'));
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    public static boolean needsFrameComputation(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.SKIP_DEBUG);
        // Pre-Java-7 class files do not require StackMapTable verification metadata.
        if ((owner.version & 0xffff) < Opcodes.V1_7) return false;
        for (MethodNode method : owner.methods) {
            boolean nonlinear = !method.tryCatchBlocks.isEmpty();
            boolean hasFrames = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FrameNode) hasFrames = true;
                if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                        || instruction instanceof LookupSwitchInsnNode) nonlinear = true;
            }
            if (nonlinear && !hasFrames) return true;
        }
        return false;
    }
}
