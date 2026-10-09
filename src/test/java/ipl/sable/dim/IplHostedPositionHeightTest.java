package ipl.sable.dim;

import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the compiled lookup and merged override with world fixtures; no Minecraft startup. */
public class IplHostedPositionHeightTest {
    private static final String HELPER = "ipl/sable/dim/IplHostedPositionHeight";
    private static final String MIXIN = "ipl/sable/mixin/IplHostedPositionHeightMixin";
    private static final String ACCELERATOR = "ipl/sable/mixin/IplLevelAcceleratorOverrideMixin";

    public record Pos(int x, int y, int z) {
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
    }
    public record Dimension(int minY, int height) {}
    public static class World implements LevelHeightAccessor {
        boolean hosting;
        Dimension dimension = new Dimension(-64, 384);
        World hostingWorld;
        Container container;
        int scalarMin = -64, scalarHeight = 384, hostingLookups, containerReads;
        public Dimension dimensionType() { return dimension; }
        public int getMinBuildHeight() { return scalarMin; }
        public int getHeight() { return scalarHeight; }
    }
    public static class Routing {
        public static boolean isHostingLevel(World world) { return world != null && world.hosting; }
        public static Container getHostingContainerFor(World world) {
            world.hostingLookups++;
            return world.hostingWorld == null ? null : world.hostingWorld.container;
        }
    }
    public static class Container {
        final World owner;
        final Map<Long, Plot> plots = new HashMap<>();
        int plotReads;
        Container(World owner) { this.owner = owner; owner.container = this; }
        public static Container getContainer(World world) { world.containerReads++; return world.container; }
        public World getLevel() { return owner; }
        public boolean inBounds(int x, int z) { return x >= -20 && x <= 20 && z >= -20 && z <= 20; }
        public Plot getPlot(int x, int z) { plotReads++; return plots.get(key(x, z)); }
        void put(int x, int z, Body body) { plots.put(key(x, z), new Plot(body)); }
        private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    }
    public static class Body {
        final World owner;
        boolean removed;
        Body(World owner) { this.owner = owner; }
        public World getLevel() { return owner; }
        public boolean isRemoved() { return removed; }
    }
    public record Plot(Body body) { public Body getSubLevel() { return body; } }
    public record State(String name) {}
    public record Chunk(int minY, int maxY, State state) {
        public int getMinBuildHeight() { return minY; }
        public int getMaxBuildHeight() { return maxY; }
        public State getBlockState(Pos pos) { return state; }
    }
    public static class TerrainOverride { public static World get() { return null; } }

    @Test void parentAndHostingReadsUseFullStorageRangeDespiteContextualTerrainBounds() throws Exception {
        var f = fixture();
        World hosting = hosting();
        hosting.container.put(3, -2, new Body(hosting));
        for (World caller : List.of(f.newWorld(), f.newWorld(), f.newWorld())) {
            // The same Level override serves server, client, and hosting-world contexts.
            caller.hostingWorld = hosting;
            caller.scalarMin = 1024;
            caller.scalarHeight = 16;
            for (int y : new int[]{-2032, -65, 0, 1000, 2031}) {
                assertFalse(f.outside(caller, new Pos(48, y, -17)));
            }
            assertTrue(f.outside(caller, new Pos(48, -2033, -17)));
            assertTrue(f.outside(caller, new Pos(48, 2032, -17)));
        }
        World caller = f.newWorld();
        caller.hosting = true;
        caller.dimension = hosting.dimension;
        caller.scalarMin = 0;
        caller.scalarHeight = 256;
        new Container(caller).put(3, -2, new Body(caller));
        assertFalse(f.outside(caller, new Pos(48, 1000, -17)));
        assertEquals(0, caller.hostingLookups, "The hosting world uses its own container");
        assertEquals(1, caller.containerReads);
    }

    @Test void missingPlotAndOrdinaryTerrainKeepTheExistingParentHeightCheck() throws Exception {
        var f = fixture();
        World caller = f.newWorld(), hosting = hosting();
        caller.hostingWorld = hosting;
        caller.scalarMin = 512;
        caller.scalarHeight = 32;
        assertTrue(f.outside(caller, new Pos(48, 0, -17)));
        assertFalse(f.outside(caller, new Pos(48, 512, -17)));
        assertFalse(f.outside(caller, new Pos(48, 543, -17)));
        assertTrue(f.outside(caller, new Pos(48, 544, -17)));
        int reads = hosting.container.plotReads;
        assertTrue(f.outside(caller, new Pos(20_000_000, 0, 20_000_000)));
        assertEquals(reads, hosting.container.plotReads, "Out-of-grid coordinates never probe plots");
        caller.hostingWorld = null;
        assertFalse(f.outside(caller, new Pos(48, 520, -17)));
    }

    @Test void removedForeignAndStaleOwnersDoNotTurnTerrainIntoHostedStorage() throws Exception {
        var f = fixture();
        World caller = f.newWorld(), hosting = hosting();
        caller.hostingWorld = hosting;
        World staleHosting = hosting();
        for (Body body : List.of(new Body(caller), new Body(staleHosting))) {
            hosting.container.put(3, -2, body);
            assertTrue(f.outside(caller, new Pos(48, 1000, -17)));
        }
        Body removed = new Body(hosting);
        removed.removed = true;
        hosting.container.put(3, -2, removed);
        assertTrue(f.outside(caller, new Pos(48, 1000, -17)));
        hosting.container.put(3, -2, new Body(hosting));
        hosting.hosting = false;
        assertTrue(f.outside(caller, new Pos(48, 1000, -17)));
    }

    @Test void storageProfileComesFromActualOwnerDimensionType() throws Exception {
        var f = fixture();
        World caller = f.newWorld(), hosting = hosting();
        caller.hostingWorld = hosting;
        hosting.container.put(3, -2, new Body(hosting));
        hosting.dimension = new Dimension(-96, 608);
        hosting.scalarMin = 1024;
        hosting.scalarHeight = 16;
        assertFalse(f.outside(caller, new Pos(48, -96, -17)));
        assertFalse(f.outside(caller, new Pos(48, 511, -17)));
        assertTrue(f.outside(caller, new Pos(48, -97, -17)));
        assertTrue(f.outside(caller, new Pos(48, 512, -17)));
    }

    @Test void packetMaximumFollowsTargetStorageAndPreservesTheSuppliedTerrainFallback() throws Exception {
        var f = fixture();
        World caller = f.newWorld(), hosting = hosting();
        caller.hostingWorld = hosting;
        caller.scalarMin = 0;
        caller.scalarHeight = 256;
        Body body = new Body(hosting);
        hosting.container.put(3, -2, body);
        // A high plot remains interactive after moving into a shorter parent dimension.
        assertEquals(2032, f.maximum(caller, new Pos(48, 2024, -17), 256));
        assertEquals(2032, f.maximum(caller, new Pos(48, 2032, -17), 256),
            "The upper endpoint is still rejected by the packet's >= comparison");
        hosting.dimension = new Dimension(-96, 608);
        assertEquals(512, f.maximum(caller, new Pos(48, 208, -17), 256));
        assertEquals(1031, f.maximum(caller, new Pos(0, 208, 0), 1031),
            "Preserve the passed maximum exactly, including other wrappers' decisions");
        body.removed = true;
        assertEquals(256, f.maximum(caller, new Pos(48, 2024, -17), 256));
        hosting.container.put(3, -2, new Body(caller));
        assertEquals(256, f.maximum(caller, new Pos(48, 2024, -17), 256));
        caller.hostingWorld = null;
        assertEquals(-2016, f.maximum(caller, new Pos(48, -2024, -17), -2016));
    }

    @Test void equalAcceleratorMinimaStillRequireChunkNativeMaximum() throws Exception {
        ClassNode source = read(ACCELERATOR);
        MethodNode handler = source.methods.stream().filter(m -> m.name.equals("ipl$readBlockStateWithChunkProfile"))
            .findFirst().orElseThrow();
        handler.access = Opcodes.ACC_PUBLIC;
        ClassNode node = bare("ipl/sable/dim/HeightAcceleratorFixture");
        node.methods.add(handler);
        for (String field : List.of("minBuildHeight", "maxBuildHeight")) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, field, "I", null, null));
        }
        var names = mappings();
        names.put(ACCELERATOR, node.name);
        Class<?> type = new Loader().define(remap(node, names));
        Object accelerator = type.getConstructor().newInstance();
        type.getField("minBuildHeight").setInt(accelerator, -2032);
        type.getField("maxBuildHeight").setInt(accelerator, -1648);
        var callback = new CallbackInfoReturnable<State>("getBlockState", true);
        State stone = new State("stone");
        Method method = type.getMethod(handler.name, Chunk.class, Pos.class, CallbackInfoReturnable.class);
        method.invoke(accelerator, new Chunk(-2032, 2032, stone), new Pos(48, 1000, -17), callback);
        assertTrue(callback.isCancelled());
        assertSame(stone, callback.getReturnValue());
        callback = new CallbackInfoReturnable<>("getBlockState", true);
        method.invoke(accelerator, new Chunk(-2032, -1648, stone), new Pos(48, -2000, -17), callback);
        assertFalse(callback.isCancelled(), "An identical profile preserves the accelerator's original path");
    }

    @Test void overrideTargetsInheritedBlockPosMethodAndLookupNeverLoadsChunks() throws Exception {
        String descriptor = "(Lnet/minecraft/core/BlockPos;)Z";
        assertTrue(read("net/minecraft/world/level/Level").methods.stream()
            .noneMatch(m -> m.name.equals("isOutsideBuildHeight") && m.desc.equals(descriptor)));
        assertTrue(read("net/minecraft/world/level/LevelHeightAccessor").methods.stream()
            .anyMatch(m -> m.name.equals("isOutsideBuildHeight") && m.desc.equals(descriptor)));
        assertTrue(read(MIXIN).methods.stream()
            .anyMatch(m -> m.name.equals("isOutsideBuildHeight") && m.desc.equals(descriptor)));
        for (MethodNode method : read(HELPER).methods) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("getChunk", call.name);
                assertNotEquals("getMinBuildHeight", call.name, "Do not read contextual storage bounds");
                assertNotEquals("getMaxBuildHeight", call.name, "Do not read contextual storage bounds");
            }
        }
    }

    private static World hosting() {
        World world = new World();
        world.hosting = true;
        world.dimension = new Dimension(-2032, 4064);
        new Container(world);
        return world;
    }
    private record Fixture(Class<?> type, Method method, Method maximumMethod) {
        World newWorld() throws Exception { return (World) type.getConstructor().newInstance(); }
        boolean outside(World world, Pos pos) throws Exception { return (boolean) method.invoke(world, pos); }
        int maximum(World world, Pos pos, int fallback) throws Exception {
            return (int) maximumMethod.invoke(null, world, pos, fallback);
        }
    }
    private static Fixture fixture() throws Exception {
        var names = mappings();
        String helperName = "ipl/sable/dim/PositionHeightHelperFixture";
        String mixinName = "ipl/sable/dim/PositionHeightWorldFixture";
        names.put(HELPER, helperName);
        names.put(MIXIN, mixinName);
        Loader loader = new Loader();
        Class<?> helper = loader.define(remap(read(HELPER), names));
        ClassNode mixin = read(MIXIN);
        mixin.superName = Type.getInternalName(World.class);
        mixin.access &= ~Opcodes.ACC_ABSTRACT;
        for (MethodNode method : mixin.methods) if (method.name.equals("<init>")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.name.equals("<init>") && call.owner.equals("java/lang/Object")) call.owner = mixin.superName;
        }
        Class<?> type = loader.define(remap(mixin, names));
        return new Fixture(type, type.getMethod("isOutsideBuildHeight", Pos.class),
            helper.getMethod("maximumForPosition", World.class, Pos.class, int.class));
    }
    private static Map<String, String> mappings() {
        var names = new HashMap<String, String>();
        names.put("net/minecraft/world/level/Level", Type.getInternalName(World.class));
        names.put("net/minecraft/core/BlockPos", Type.getInternalName(Pos.class));
        names.put("net/minecraft/world/level/dimension/DimensionType", Type.getInternalName(Dimension.class));
        names.put("dev/ryanhcode/sable/api/sublevel/SubLevelContainer", Type.getInternalName(Container.class));
        names.put("dev/ryanhcode/sable/sublevel/plot/LevelPlot", Type.getInternalName(Plot.class));
        names.put("dev/ryanhcode/sable/sublevel/SubLevel", Type.getInternalName(Body.class));
        names.put("ipl/sable/dim/IplDimAgnostic", Type.getInternalName(Routing.class));
        names.put("net/minecraft/world/level/chunk/LevelChunk", Type.getInternalName(Chunk.class));
        names.put("net/minecraft/world/level/block/state/BlockState", Type.getInternalName(State.class));
        names.put("ipl/sable/transit/IplTerrainReadOverride", Type.getInternalName(TerrainOverride.class));
        return names;
    }
    private static ClassNode bare(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        return node;
    }
    private static byte[] remap(ClassNode node, Map<String, String> names) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        return writer.toByteArray();
    }
    private static ClassNode read(String name) throws Exception {
        try (var input = IplHostedPositionHeightTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
