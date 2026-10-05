package ipl.sable.dim;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IplLightChunkPositionTest {
    private record Chunk(int x, int z) {}

    @Test void sameWorldFallbackAtZeroCannotSatisfyAHostedPlotQuery() {
        Object world = new Object();
        Chunk fallback = new Chunk(0, 0);
        assertSame(fallback, IplLightChunkOwnership.select(world, world, fallback),
            "Negative control: .64's owner-only check accepts this fallback");
        assertNull(IplLightChunkOwnership.selectPosition(1280064, 1280448,
            fallback.x(), fallback.z(), false, fallback),
            "Even a non-placeholder chunk from the wrong coordinate is invalid");
    }

    @Test void placeholderIsMissingEvenWhenItsStoredPositionMatchesTheQuery() {
        Chunk placeholder = new Chunk(0, 0);
        assertNull(IplLightChunkOwnership.selectPosition(0, 0, 0, 0, true, placeholder));
    }

    @Test void loadedEmptyTerrainAndRealHostedChunksRetainTheirLighting() {
        for (Chunk chunk : List.of(new Chunk(0, 0), new Chunk(-37, 19), new Chunk(1280064, 1280448))) {
            assertSame(chunk, IplLightChunkOwnership.selectPosition(chunk.x(), chunk.z(),
                chunk.x(), chunk.z(), false, chunk));
        }
    }

    @Test void eitherCoordinateMismatchIsRejectedRatherThanRebased() {
        Chunk chunk = new Chunk(-37, 19);
        assertNull(IplLightChunkOwnership.selectPosition(-36, 19, chunk.x(), chunk.z(), false, chunk));
        assertNull(IplLightChunkOwnership.selectPosition(-37, 20, chunk.x(), chunk.z(), false, chunk));
        assertEquals(new Chunk(-37, 19), chunk);
    }

    /**
     * Runs the installed ScalableLux indexing bytecode, not a reimplementation of
     * its formula. Only these two self-contained methods and their fields are
     * transplanted into a test fixture; no Minecraft world or Mixin is started.
     */
    @Test void installedScalableLuxReproducesTheReportedIndexAndAcceptsOnlyLocalCacheCoordinates() throws Exception {
        String original = "ca/spottedleaf/starlight/common/light/StarLightEngine";
        ClassNode source = new ClassNode();
        try (var in = getClass().getResourceAsStream("/" + original + ".class")) {
            Assumptions.assumeTrue(in != null, "Add the installed ScalableLux JAR to testRuntimeOnly");
            new ClassReader(in).accept(source, 0);
        }
        String generated = "ipl/sable/dim/ScalableLuxCacheIndexFixture";
        ClassNode fixture = new ClassNode();
        fixture.version = Opcodes.V21;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = generated;
        fixture.superName = "java/lang/Object";
        var fields = new LinkedHashMap<String, String>();
        for (String name : List.of("setupEncodeOffset", "getEmptinessMap")) {
            String descriptor = name.equals("setupEncodeOffset") ? "(III)V" : "(II)[Z";
            MethodNode method = source.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow();
            method.access = Opcodes.ACC_PUBLIC;
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    assertEquals(original, field.owner);
                    fields.put(field.name, field.desc);
                    field.owner = generated;
                } else if (instruction instanceof MethodInsnNode call) {
                    fail("Index methods unexpectedly require another implementation: " + call.owner + "." + call.name);
                }
            }
            fixture.methods.add(method);
        }
        fields.forEach((name, descriptor) -> fixture.fields.add(
            new FieldNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null)));
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.methods.add(constructor);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        class Loader extends ClassLoader {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        Class<?> type = new Loader().define(writer.toByteArray());
        Object engine = type.getConstructor().newInstance();
        int x = 1280064, z = 1280448;
        type.getMethod("setupEncodeOffset", int.class, int.class, int.class)
            .invoke(engine, x * 16 + 7, 128, z * 16 + 7);
        boolean[][] cache = new boolean[25][];
        for (int i = 0; i < cache.length; i++) cache[i] = new boolean[]{(i & 1) == 0};
        type.getField("emptinessMapCache").set(engine, cache);
        var lookup = type.getMethod("getEmptinessMap", int.class, int.class);
        assertEquals(-7682292, type.getField("chunkIndexOffset").getInt(engine));
        var thrown = assertThrows(InvocationTargetException.class, () -> lookup.invoke(engine, 0, 0));
        assertInstanceOf(ArrayIndexOutOfBoundsException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("-7682292"));
        assertSame(cache[12], lookup.invoke(engine, x, z));
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            assertSame(cache[12 + dx + 5 * dz], lookup.invoke(engine, x + dx, z + dz));
        }
        assertNull(IplLightChunkOwnership.selectPosition(x, z, 0, 0, true, new Chunk(0, 0)),
            "The production boundary removes the invalid center before ScalableLux consumes its position");
    }
}
