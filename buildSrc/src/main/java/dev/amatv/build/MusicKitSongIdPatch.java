package dev.amatv.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Numeric metadata compatibility only; the official SDK remains the playback engine. */
public final class MusicKitSongIdPatch {
    public static final String CLASS_NAME = "com.apple.android.music.playback.g.h";
    public static final String INTERNAL_NAME = CLASS_NAME.replace('.', '/');
    public static final String CLASS_ENTRY = INTERNAL_NAME + ".class";
    public static final String METHOD_DESCRIPTOR = "(Landroid/util/JsonReader;)Ljava/util/List;";
    public static final String EXPECTED_AAR_SHA256 =
            "39bc28cbc2413c0ce0477c1ef4d3b9e51bb163a7723d930c46bc089715116e3c";
    public static final String EXPECTED_CLASS_SHA256 =
            "716b8ce39c077282b1ee02eb734001d47c910cbf45ecb472d91b5df0ed8f6ff5";
    private static final String INT_READER = "(Landroid/util/JsonReader;I)I";
    private static final String LONG_READER = "(Landroid/util/JsonReader;J)J";

    private MusicKitSongIdPatch() {}

    public static void verifySdk(Path aar) throws IOException {
        require(EXPECTED_AAR_SHA256.equals(sha256(Files.readAllBytes(aar))),
                "Unsupported MusicKit playback AAR: " + aar.getFileName()
                        + ". Expected the official unmodified 1.1.1 SDK (SHA-256 "
                        + EXPECTED_AAR_SHA256 + "). Review the songId compatibility patch before upgrading.");
        require(EXPECTED_CLASS_SHA256.equals(sha256(readParserClass(aar))),
                "Unsupported MusicKit song metadata parser. Review the songId compatibility patch.");
    }

    public static byte[] readParserClass(Path aar) throws IOException {
        try (ZipFile archive = new ZipFile(aar.toFile())) {
            var jarEntry = archive.getEntry("classes.jar");
            require(jarEntry != null, "MusicKit AAR does not contain classes.jar.");
            try (InputStream input = archive.getInputStream(jarEntry);
                    ZipInputStream classes = new ZipInputStream(input)) {
                java.util.zip.ZipEntry entry;
                while ((entry = classes.getNextEntry()) != null) {
                    if (CLASS_ENTRY.equals(entry.getName())) return classes.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("MusicKit AAR does not contain " + CLASS_ENTRY);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Change exactly the songId local and its existing numeric reader call. */
    public static void apply(ClassNode owner) {
        require(INTERNAL_NAME.equals(owner.name), "Unexpected MusicKit parser class: " + owner.name);
        var methods = owner.methods.stream()
                .filter(it -> it.name.equals("a") && it.desc.equals(METHOD_DESCRIPTOR)).toList();
        require(methods.size() == 1, "Unsupported MusicKit song-list parser signature.");
        MethodNode method = methods.get(0);
        require((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC),
                "Unsupported MusicKit song-list parser access.");
        require(owner.methods.stream().anyMatch(it -> it.name.equals("a") && it.desc.equals(LONG_READER)),
                "MusicKit parser has no existing 64-bit JSON reader helper.");

        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
        }
        var calls = instructions.stream().filter(it -> it instanceof MethodInsnNode)
                .map(it -> (MethodInsnNode) it)
                .filter(it -> it.getOpcode() == Opcodes.INVOKESTATIC && it.owner.equals(INTERNAL_NAME)
                        && it.name.equals("a") && it.desc.equals(INT_READER)).toList();
        require(calls.size() == 1, "Unsupported MusicKit songId reader shape (expected one int reader).");
        MethodInsnNode call = calls.get(0);
        int callIndex = instructions.indexOf(call);
        require(callIndex > 0 && instructions.get(callIndex - 1).getOpcode() == Opcodes.ICONST_0
                        && instructions.get(callIndex + 1) instanceof VarInsnNode
                        && instructions.get(callIndex + 1).getOpcode() == Opcodes.ISTORE,
                "Unsupported MusicKit songId default/store shape.");
        int songIdLocal = ((VarInsnNode) instructions.get(callIndex + 1)).var;
        List<VarInsnNode> stores = instructions.stream().filter(it -> it instanceof VarInsnNode)
                .map(it -> (VarInsnNode) it)
                .filter(it -> it.var == songIdLocal && it.getOpcode() == Opcodes.ISTORE).toList();
        List<VarInsnNode> loads = instructions.stream().filter(it -> it instanceof VarInsnNode)
                .map(it -> (VarInsnNode) it)
                .filter(it -> it.var == songIdLocal && it.getOpcode() == Opcodes.ILOAD).toList();
        require(stores.size() == 2 && loads.size() == 1,
                "Unsupported MusicKit songId local uses.");
        require(instructions.stream().filter(it -> it.getOpcode() == Opcodes.ICONST_0).count() == 2,
                "Unsupported MusicKit songId zero initializers.");
        VarInsnNode initialStore = stores.get(0);
        require(instructions.get(instructions.indexOf(initialStore) - 1).getOpcode() == Opcodes.ICONST_0,
                "Unsupported MusicKit songId initial value.");
        VarInsnNode load = loads.get(0);
        int loadIndex = instructions.indexOf(load);
        require(instructions.get(loadIndex + 1).getOpcode() == Opcodes.I2L
                        && instructions.get(loadIndex + 2) instanceof MethodInsnNode
                        && ((MethodInsnNode) instructions.get(loadIndex + 2)).owner
                                .equals("com/apple/android/music/playback/g/b")
                        && ((MethodInsnNode) instructions.get(loadIndex + 2)).name.equals("a")
                        && ((MethodInsnNode) instructions.get(loadIndex + 2)).desc.equals("(J)V"),
                "Unsupported MusicKit songId asset assignment shape.");

        // Allocate a new two-slot local; every unrelated local keeps its original slot.
        int longLocal = method.maxLocals;
        method.maxLocals += 2;
        method.instructions.set(instructions.get(instructions.indexOf(initialStore) - 1),
                new InsnNode(Opcodes.LCONST_0));
        method.instructions.set(instructions.get(callIndex - 1), new InsnNode(Opcodes.LCONST_0));
        call.desc = LONG_READER;
        for (VarInsnNode store : stores) {
            store.setOpcode(Opcodes.LSTORE);
            store.var = longLocal;
        }
        load.setOpcode(Opcodes.LLOAD);
        load.var = longLocal;
        method.instructions.remove(instructions.get(loadIndex + 1));
        if (method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.index == songIdLocal && "I".equals(local.desc)) {
                    local.index = longLocal;
                    local.desc = "J";
                }
            }
        }
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) method.instructions.remove(instruction);
        }
        // AGP recomputes frames/maxima for this instrumented method.
        method.maxStack += 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
