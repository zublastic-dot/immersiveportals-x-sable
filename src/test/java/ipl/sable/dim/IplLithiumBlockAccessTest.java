package ipl.sable.dim;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the installed optimized lookup and our compiled handler, without starting Minecraft. */
public class IplLithiumBlockAccessTest {
    private static final String LITHIUM = "net/caffeinemc/mods/lithium/mixin/world/inline_block_access/LevelMixin";
    private static final String PATCH = "ipl/sable/mixin/compat/IplLithiumBlockAccessMixin";
    public record State(String name) {}
    public record Pos(int x, int y, int z) {
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
    }
    public static class SectionPos {
        public static int blockToSectionCoord(int value) { return value >> 4; }
    }
    public static class Section {
        final State state;
        Section(State state) { this.state = state; }
        public boolean hasOnlyAir() { return state == AIR; }
        public State getBlockState(int x, int y, int z) { return state; }
    }
    public static class World {
        int minY;
        int chunkReads;
        int indexReads;
        Chunk chunk;
        public Chunk getChunk(int x, int z) { chunkReads++; return chunk; }
        public int getSectionIndex(int y) { indexReads++; return (y >> 4) - (minY >> 4); }
    }
    public static class Chunk {
        final World owner;
        final Section[] sections;
        boolean empty;
        int indexReads;
        Chunk(World owner, int height) {
            this.owner = owner;
            sections = new Section[height / 16];
            for (int i = 0; i < sections.length; i++) sections[i] = new Section(AIR);
        }
        public World getLevel() { return owner; }
        public Section[] getSections() { return sections; }
        public boolean isEmpty() { return empty; }
        public int getSectionIndex(int y) { indexReads++; return (y >> 4) - (owner.minY >> 4); }
        void at(int y, State state) { sections[(y >> 4) - (owner.minY >> 4)] = new Section(state); }
    }
    private static final State AIR = new State("air"), VOID = new State("void"), OBSIDIAN = new State("obsidian");
    private static final ThreadLocal<Method> HANDLER = new ThreadLocal<>();

    // This is only the Mixin invocation glue. The handler itself is loaded from the
    // production class bytecode, with World/Chunk types replaced by these fixtures.
    public static int invokeHandler(World caller, int y, Chunk chunk) {
        Operation<Integer> original = args -> {
            assertSame(caller, args[0]);
            assertEquals(y, args[1]);
            return caller.getSectionIndex(y);
        };
        try {
            return (int) HANDLER.get().invoke(null, caller, y, original, chunk);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test void exactReportedParentReadUsesHosted205InsteadOf237() throws Exception {
        var f = fixture();
        World owner = world(-64);
        Chunk chunk = new Chunk(owner, 384);
        chunk.at(205, OBSIDIAN);
        assertSame(AIR, f.read(false, -96, chunk, 205), "The reported visible block reads as air");
        State above = new State("block_at_237");
        chunk.at(237, above);
        assertSame(above, f.read(false, -96, chunk, 205));
        assertSame(OBSIDIAN, f.read(true, -96, chunk, 205));
        assertEquals(1, f.patched.chunkReads, "The already-fetched chunk must be reused");
        assertEquals(0, f.patched.indexReads, "Foreign reads must not use the caller's origin");
        assertEquals(1, chunk.indexReads);
    }

    @Test void actualForeignSectionBoundsAndAirRemainAuthoritative() throws Exception {
        var f = fixture();
        Chunk chunk = new Chunk(world(-64), 384);
        chunk.at(-64, OBSIDIAN);
        chunk.at(319, OBSIDIAN);
        assertSame(OBSIDIAN, f.read(true, 0, chunk, -64));
        assertSame(OBSIDIAN, f.read(true, 0, chunk, 319));
        assertSame(VOID, f.read(true, -512, chunk, -65));
        assertSame(VOID, f.read(true, -512, chunk, 320));
        assertSame(AIR, f.read(true, 0, chunk, 64));
        chunk.empty = true;
        assertSame(VOID, f.read(true, 0, chunk, -64));
    }

    @Test void arbitraryHeightOriginsAndSameProfileDifferentWorldsWork() throws Exception {
        var f = fixture();
        for (int minY : new int[]{-512, -96, -64, 0, 128}) {
            Chunk chunk = new Chunk(world(minY), 192);
            chunk.at(minY + 35, OBSIDIAN);
            for (int parentMin : new int[]{-1024, -64, 0, 256, minY}) {
                assertSame(OBSIDIAN, f.read(true, parentMin, chunk, minY + 35));
                assertEquals(0, f.patched.indexReads);
            }
        }
    }

    @Test void sameOwnerKeepsOriginalIndexCallAndOnlyOneChunkLookup() throws Exception {
        var f = fixture();
        World caller = f.patched;
        caller.minY = -96;
        caller.chunk = new Chunk(caller, 608);
        caller.chunk.at(205, OBSIDIAN);
        HANDLER.set(f.handler);
        try {
            assertSame(OBSIDIAN, f.patchedRead.invoke(caller, new Pos(12, 205, 8)));
        } finally {
            HANDLER.remove();
        }
        assertEquals(1, caller.indexReads);
        assertEquals(0, caller.chunk.indexReads);
        assertEquals(1, caller.chunkReads);
    }

    @Test void wiringIsOptionalCommonSideAndFollowsLithiumOverwrite() throws Exception {
        var source = readResource(PATCH);
        AnnotationNode mixin = source.invisibleAnnotations.stream()
            .filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        assertEquals(900, value(mixin, "priority"));
        assertEquals(List.of(Type.getObjectType("net/minecraft/world/level/Level")), value(mixin, "value"));
        MethodNode handler = source.methods.stream().filter(m -> m.name.equals("ipl$indexInChunkOwner")).findFirst().orElseThrow();
        AnnotationNode wrap = handler.visibleAnnotations.stream()
            .filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(List.of("getBlockState"), value(wrap, "method"));
        assertEquals(0, value(wrap, "require"));
        Object ats = value(wrap, "at");
        AnnotationNode at = ats instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) ats;
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("Lnet/minecraft/world/level/Level;getSectionIndex(I)I", value(at, "target"));
        assertNotNull(handler.invisibleParameterAnnotations);
        assertTrue(handler.invisibleParameterAnnotations[3].stream().anyMatch(a -> a.desc.endsWith("/Local;")));
        try (var in = getClass().getResourceAsStream("/ipl_sable.mixins.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.substring(0, json.indexOf("\"client\"")).contains("compat.IplLithiumBlockAccessMixin"));
        }
        assertTrue(handler.instructions.iterator().hasNext());
        for (var i : handler.instructions) if (i instanceof MethodInsnNode call) {
            assertNotEquals("getChunk", call.name, "Must not issue a second chunk lookup");
        }
    }

    @Test void installedLithiumHasOneIndexSiteAndOneCapturableChunkLocal() throws Exception {
        var node = lithium();
        var method = lookup(node);
        long sites = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
            .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(node.name)
                && call.name.equals("getSectionIndex") && call.desc.equals("(I)I")).count();
        assertEquals(1, sites);
        List<LocalVariableNode> chunks = method.localVariables.stream()
            .filter(local -> local.desc.equals("Lnet/minecraft/world/level/chunk/LevelChunk;")).toList();
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.getFirst().index);
    }

    private static World world(int minY) { World world = new World(); world.minY = minY; return world; }
    private record Fixture(World baseline, World patched, Method baselineRead, Method patchedRead, Method handler) {
        State read(boolean fixed, int minY, Chunk chunk, int y) throws Exception {
            World target = fixed ? patched : baseline;
            target.minY = minY;
            target.chunk = chunk;
            target.chunkReads = target.indexReads = 0;
            HANDLER.set(handler);
            try {
                return (State) (fixed ? patchedRead : baselineRead).invoke(target, new Pos(20481031, y, 20485128));
            } finally {
                HANDLER.remove();
            }
        }
    }

    private static Fixture fixture() throws Exception {
        ClassNode patch = readResource(PATCH);
        MethodNode handler = patch.methods.stream().filter(m -> m.name.equals("ipl$indexInChunkOwner")).findFirst().orElseThrow();
        handler.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        ClassNode handlerClass = bare("ipl/sable/dim/IndexHandlerFixture", "java/lang/Object");
        handlerClass.methods.add(handler);
        Class<?> handlerType = define(handlerClass, Map.of(PATCH, handlerClass.name));
        Method call = handlerType.getMethod(handler.name, World.class, int.class, Operation.class, Chunk.class);
        Class<?> baseline = lookupFixture(false), patched = lookupFixture(true);
        return new Fixture((World) baseline.getConstructor().newInstance(), (World) patched.getConstructor().newInstance(),
            baseline.getMethod("getBlockState", Pos.class), patched.getMethod("getBlockState", Pos.class), call);
    }

    private static Class<?> lookupFixture(boolean fixed) throws Exception {
        ClassNode source = lithium();
        MethodNode method = lookup(source);
        String generated = "ipl/sable/dim/LithiumLookup" + (fixed ? "Fixed" : "Original");
        if (fixed) for (var i : method.instructions.toArray()) {
            if (i instanceof MethodInsnNode call && call.owner.equals(source.name) && call.name.equals("getSectionIndex")) {
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 2));
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    Type.getInternalName(IplLithiumBlockAccessTest.class), "invokeHandler",
                    "(" + Type.getDescriptor(World.class) + "I" + Type.getDescriptor(Chunk.class) + ")I", false));
            }
        }
        ClassNode fixture = bare(generated, Type.getInternalName(World.class));
        fixture.methods.add(method);
        for (String field : List.of("OUTSIDE_WORLD_BLOCK", "INSIDE_WORLD_DEFAULT_BLOCK")) {
            fixture.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field,
                "Lnet/minecraft/world/level/block/state/BlockState;", null, null));
        }
        Class<?> type = define(fixture, Map.of(source.name, generated));
        type.getField("OUTSIDE_WORLD_BLOCK").set(null, VOID);
        type.getField("INSIDE_WORLD_DEFAULT_BLOCK").set(null, AIR);
        return type;
    }

    private static ClassNode bare(String name, String parent) {
        ClassNode fixture = new ClassNode();
        fixture.version = Opcodes.V21;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = name;
        fixture.superName = parent;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.methods.add(constructor);
        return fixture;
    }

    private static Class<?> define(ClassNode fixture, Map<String, String> extra) {
        var names = new java.util.HashMap<>(extra);
        names.put("net/minecraft/world/level/Level", Type.getInternalName(World.class));
        names.put("net/minecraft/core/BlockPos", Type.getInternalName(Pos.class));
        names.put("net/minecraft/core/SectionPos", Type.getInternalName(SectionPos.class));
        names.put("net/minecraft/world/level/chunk/LevelChunk", Type.getInternalName(Chunk.class));
        names.put("net/minecraft/world/level/chunk/LevelChunkSection", Type.getInternalName(Section.class));
        names.put("net/minecraft/world/level/block/state/BlockState", Type.getInternalName(State.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fixture.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
        return new Loader().define(writer.toByteArray());
    }

    private static MethodNode lookup(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals("getBlockState")).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
    private static ClassNode readResource(String name) throws Exception {
        try (var in = IplLithiumBlockAccessTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(in, name);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
    private static ClassNode lithium() throws Exception {
        String path = System.getProperty("ip.portal.test.lithiumJar", "");
        if (path.isEmpty()) {
            Assumptions.assumeTrue(IplLithiumBlockAccessTest.class.getResource("/" + LITHIUM + ".class") != null,
                "Supply the installed Lithium JAR as ip.portal.test.lithiumJar or a test runtime dependency");
            return readResource(LITHIUM);
        }
        assertTrue(Files.isRegularFile(Path.of(path)));
        try (ZipFile zip = new ZipFile(path); var in = zip.getInputStream(zip.getEntry(LITHIUM + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
