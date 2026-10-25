package dev.amatv.build;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Executes Apple's original parser classes with Android's actual JsonReader. */
public class MusicKitSongIdPatchTest {
    private static Path aar;
    private static Path sdkClasses;
    private static byte[] originalParser;

    @BeforeClass
    public static void readLocalOfficialSdk() throws IOException {
        aar = Path.of(System.getProperty("amatv.musicKitSdkAar"));
        Assume.assumeTrue("Download the official SDK to run binary compatibility tests", Files.isRegularFile(aar));
        MusicKitSongIdPatch.verifySdk(aar);
        originalParser = MusicKitSongIdPatch.readParserClass(aar);
        sdkClasses = Files.createTempFile("amatv-official-sdk-classes-", ".jar");
        sdkClasses.toFile().deleteOnExit();
        try (ZipFile zip = new ZipFile(aar.toFile()); InputStream input = zip.getInputStream(zip.getEntry("classes.jar"))) {
            Files.write(sdkClasses, input.readAllBytes());
        }
    }

    @Test
    public void originalParserReproducesOverflowAtCurrentCatalogId() throws Exception {
        try (ParserLoader original = new ParserLoader(false)) {
            assertEquals(2_147_483_647L, songIds(original.parse(metadata("2147483647"))).get(0).longValue());
            try {
                original.parse(metadata("6781751954"));
                fail("Original SDK should reproduce the 32-bit songId overflow");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause().toString(), expected.getCause() instanceof NumberFormatException);
            }
        }
    }

    @Test
    public void patchedActualParserPreservesAll64BitIds() throws Exception {
        try (ParserLoader patched = new ParserLoader(true)) {
            for (long id : new long[] {0L, 2_147_483_647L, 2_147_483_648L, 6_781_751_954L, Long.MAX_VALUE}) {
                assertEquals(List.of(id, id), songIds(patched.parse(metadata(Long.toString(id)))));
            }
            assertEquals(List.of(6_781_751_954L, 6_781_751_954L),
                    songIds(patched.parse(metadata("\"6781751954\""))));
        }
    }

    @Test
    public void missingAndNullSongIdsKeepExistingDefault() throws Exception {
        try (ParserLoader original = new ParserLoader(false); ParserLoader patched = new ParserLoader(true)) {
            for (String value : new String[] {"null", "0"}) {
                assertEquals(songIds(original.parse(metadata(value))), songIds(patched.parse(metadata(value))));
            }
            String missing = metadata("0").replace("\"songId\":0,", "");
            assertEquals(List.of(0L, 0L), songIds(patched.parse(missing)));
        }
    }

    @Test
    public void assetsCertificatesLicenseUrlsAndFileSizesRemainIdentical() throws Exception {
        try (ParserLoader original = new ParserLoader(false); ParserLoader patched = new ParserLoader(true)) {
            List<Map<String, Object>> expected = assetFields(original.parse(metadata("1695951894")));
            List<Map<String, Object>> actual = assetFields(patched.parse(metadata("1695951894")));
            assertEquals(expected, actual);
            assertEquals(2, actual.size()); // Preserve the SDK's existing cbcp flavor selection.
            assertEquals("https://cert.example.invalid/key", actual.get(0).get("g"));
            assertEquals("https://license.example.invalid/key", actual.get(0).get("h"));
            assertEquals(6_000_000_001L, actual.get(0).get("e"));
        }
    }

    @Test
    public void everyOtherSdkMethodRetainsIdenticalBytecode() {
        ClassNode before = read(originalParser);
        ClassNode after = read(originalParser);
        MusicKitSongIdPatch.apply(after);
        assertEquals(before.methods.size(), after.methods.size());
        for (int index = 0; index < before.methods.size(); index++) {
            MethodNode method = before.methods.get(index);
            if (method.name.equals("a") && method.desc.equals(MusicKitSongIdPatch.METHOD_DESCRIPTOR)) continue;
            assertArrayEquals(method.name + method.desc, standaloneMethod(method), standaloneMethod(after.methods.get(index)));
        }
        MethodNode target = after.methods.stream().filter(it -> it.name.equals("a")
                && it.desc.equals(MusicKitSongIdPatch.METHOD_DESCRIPTOR)).findFirst().orElseThrow();
        boolean hasLongReader = false;
        for (var instruction : target.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(MusicKitSongIdPatch.INTERNAL_NAME)) {
                assertFalse("songId int reader must be gone", call.desc.equals("(Landroid/util/JsonReader;I)I"));
                hasLongReader |= call.desc.equals("(Landroid/util/JsonReader;J)J");
            }
        }
        assertTrue(hasLongReader);
    }

    @Test
    public void unknownParserShapeFailsClearlyInsteadOfGuessing() {
        ClassNode changed = read(originalParser);
        changed.methods.removeIf(it -> it.name.equals("a") && it.desc.equals(MusicKitSongIdPatch.METHOD_DESCRIPTOR));
        try {
            MusicKitSongIdPatch.apply(changed);
            fail("Unknown parser version must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unsupported MusicKit"));
        }
    }

    @Test
    public void unknownAarFailsClearlyAndOfficialBinaryStaysUnmodified() throws IOException {
        byte[] before = Files.readAllBytes(aar);
        Path changed = Files.createTempFile("amatv-unsupported-sdk-", ".aar");
        try {
            Files.writeString(changed, "unsupported version");
            try {
                MusicKitSongIdPatch.verifySdk(changed);
                fail("Unknown SDK version must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Unsupported MusicKit playback AAR"));
            }
            assertArrayEquals(before, Files.readAllBytes(aar));
        } finally {
            Files.deleteIfExists(changed);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] parserForJvm(boolean patched) {
        ClassNode node = read(originalParser);
        if (patched) MusicKitSongIdPatch.apply(node);
        // This Android-targeted SDK omits stack-map frames. Add verification
        // metadata equally to both controls so a desktop JVM can execute them.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] standaloneMethod(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, MusicKitSongIdPatch.INTERNAL_NAME, null, "java/lang/Object", null);
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<Long> songIds(Object parsed) throws ReflectiveOperationException {
        var ids = new ArrayList<Long>();
        for (Map<String, Object> asset : assetFields(parsed)) ids.add((Long) asset.get("f"));
        return ids;
    }

    private static List<Map<String, Object>> assetFields(Object parsed) throws ReflectiveOperationException {
        var assets = (List<?>) parsed.getClass().getMethod("a").invoke(parsed);
        var result = new ArrayList<Map<String, Object>>();
        for (Object asset : assets) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (var field : asset.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                fields.put(field.getName(), field.get(asset));
            }
            result.add(fields);
        }
        return result;
    }

    private static String metadata(String songIdJson) {
        return "{\"songList\":[{\"songId\":" + songIdJson + ","
                + "\"hls-key-cert-url\":\"https://cert.example.invalid/key\","
                + "\"hls-key-server-url\":\"https://license.example.invalid/key\","
                + "\"assets\":["
                + "{\"flavor\":\"28:ctrp:256:cbcp\",\"URL\":\"https://audio.example.invalid/first\",\"file-size\":6000000001},"
                + "{\"flavor\":\"28:ctrp:64:cbcp\",\"URL\":\"https://audio.example.invalid/second\",\"file-size\":2000000},"
                + "{\"flavor\":\"unsupported\",\"URL\":\"https://audio.example.invalid/third\",\"file-size\":123}"
                + "]}],\"failureType\":\"\",\"customerMessage\":\"\"}";
    }

    private static final class ParserLoader extends URLClassLoader {
        private final byte[] replacement;

        ParserLoader(boolean patched) throws IOException {
            super(new URL[] {sdkClasses.toUri().toURL()}, MusicKitSongIdPatchTest.class.getClassLoader());
            replacement = parserForJvm(patched);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (replacement != null && name.equals(MusicKitSongIdPatch.CLASS_NAME)) {
                return defineClass(name, replacement, 0, replacement.length);
            }
            return super.findClass(name);
        }

        Object parse(String json) throws ReflectiveOperationException {
            Class<?> parser = loadClass(MusicKitSongIdPatch.CLASS_NAME);
            return parser.getMethod("a", InputStream.class).invoke(null,
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
