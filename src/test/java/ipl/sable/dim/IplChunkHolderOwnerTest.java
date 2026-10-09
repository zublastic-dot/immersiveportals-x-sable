package ipl.sable.dim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Executes both compiled ChunkHolder consumers against the actual storage wrapper. */
public class IplChunkHolderOwnerTest {
    private static final String MIXIN = "qouteall/imm_ptl/core/mixin/common/chunk_sync/MixinChunkHolder";

    public static class World implements BlockAndTintGetter {
        public final Dimension dimension = new Dimension();
        public boolean plot;
        public int minY = -96, height = 608, heightReads, plotChecks;
        public Dimension dimension() { return dimension; }
        @Override public int getMinBuildHeight() { heightReads++; return minY; }
        @Override public int getHeight() { heightReads++; return height; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public float getShade(Direction direction, boolean shade) { return 1; }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("No block reads"); }
        @Override public BlockState getBlockState(BlockPos pos) { throw new AssertionError("No block reads"); }
        @Override public FluidState getFluidState(BlockPos pos) { throw new AssertionError("No fluid reads"); }
    }
    public static class ServerWorld extends World {
        public final Server server = new Server();
        public Server getServer() { return server; }
    }
    public static class Server {}
    public static class Dimension {
        public final List<Player> watchers = List.of(new Player());
        public int trackingCalls, x, z;
        public boolean boundaryOnly;
    }
    public static class Player {}
    public static class Pos {
        public final int x, z;
        public Pos(int x, int z) { this.x = x; this.z = z; }
    }
    public interface Packet {}
    public record OriginalPacket() implements Packet {}
    public record RedirectedPacket(Server server, Dimension dimension, Packet original) implements Packet {}
    public interface Provider { List<Player> getPlayers(Pos pos, boolean boundaryOnly); }
    public static class Routing {
        public static boolean isPlotChunk(World world, Pos pos) {
            world.plotChecks++;
            return world.plot;
        }
    }
    public static class Tracking {
        public static List<Player> getPlayersViewingChunk(Dimension dimension, int x, int z, boolean boundaryOnly) {
            dimension.trackingCalls++;
            dimension.x = x;
            dimension.z = z;
            dimension.boundaryOnly = boundaryOnly;
            return dimension.watchers;
        }
    }
    public static class Packets {
        public static Packet createRedirectedMessage(Server server, Dimension dimension, Packet packet) {
            return new RedirectedPacket(server, dimension, packet);
        }
    }

    @Test void hostedBlockBroadcastUsesExactOwnerForTrackingAndPacketDimension() throws Exception {
        ServerWorld owner = new ServerWorld();
        owner.plot = true;
        var storage = IplChunkStorageHeight.select(owner, true, -2032, 4064, 254);
        assertNotSame(owner, storage);
        assertInstanceOf(BlockAndTintGetter.class, storage, "Retain ScalableLux's owner capability");
        assertThrows(ClassCastException.class, () -> ServerWorld.class.cast(storage),
            "This actual wrapper reproduces the .71 hard-cast failure");
        var holder = fixture(storage);
        Pos pos = new Pos(1280064, 1280064);
        List<Player> tracked = List.of(new Player(), new Player());
        int[] providerCalls = {0};
        Provider provider = (actualPos, boundary) -> {
            assertSame(pos, actualPos);
            assertTrue(boundary);
            providerCalls[0]++;
            return tracked;
        };

        for (int[] frame : new int[][]{{-96, 608}, {2016, 16}}) {
            owner.minY = frame[0];
            owner.height = frame[1];
            assertSame(tracked, holder.players(provider, pos, true));
            assertPacketOwner(holder, owner);
            assertSame(storage, holder.heightAccessor(), "Ownership lookup must not replace the stored height profile");
            assertEquals(-2032, storage.getMinBuildHeight());
            assertEquals(254, storage.getSectionsCount());
            assertEquals(140, storage.getSectionIndex(208));
        }
        assertEquals(2, providerCalls[0]);
        assertEquals(2, owner.plotChecks);
        assertEquals(0, owner.dimension.trackingCalls, "Hosted chunks retain Sable's player-provider branch");
        assertEquals(0, owner.heightReads, "Packet ownership must not read contextual terrain height");
    }

    @Test void ordinaryHolderKeepsOriginalWorldAndExistingTrackingBranch() throws Exception {
        ServerWorld owner = new ServerWorld();
        var storage = IplChunkStorageHeight.select(owner, false, -2032, 4064, 38);
        assertSame(owner, storage);
        var holder = fixture(storage);
        Pos pos = new Pos(-11, 21);
        Provider provider = (ignored, boundary) -> { throw new AssertionError("Ordinary chunks use IP tracking"); };
        assertSame(owner.dimension.watchers, holder.players(provider, pos, false));
        assertEquals(1, owner.dimension.trackingCalls);
        assertEquals(-11, owner.dimension.x);
        assertEquals(21, owner.dimension.z);
        assertFalse(owner.dimension.boundaryOnly);
        assertPacketOwner(holder, owner);
        assertSame(owner, holder.heightAccessor());
    }

    @Test void unwrappingPreservesUnknownAccessorsAndRecoversNestedOwnerWithoutReadingHeight() {
        var plain = LevelHeightAccessor.create(32, 64);
        assertSame(plain, IplChunkStorageHeight.unwrapOwner(plain));
        ServerWorld owner = new ServerWorld();
        assertSame(owner, IplChunkStorageHeight.unwrapOwner(owner));
        var first = IplChunkStorageHeight.select(owner, true, -2032, 4064, -1);
        var second = IplChunkStorageHeight.select(first, true, -2032, 4064, -1);
        assertSame(owner, IplChunkStorageHeight.unwrapOwner(first));
        assertSame(owner, IplChunkStorageHeight.unwrapOwner(second));
        assertEquals(254, second.getSectionsCount());
        assertEquals(0, owner.heightReads);
    }

    private static void assertPacketOwner(Fixture holder, ServerWorld owner) throws Exception {
        Packet original = new OriginalPacket();
        RedirectedPacket redirected = assertInstanceOf(RedirectedPacket.class, holder.packet(original));
        assertSame(original, redirected.original());
        assertSame(owner.server, redirected.server());
        assertSame(owner.dimension, redirected.dimension());
    }

    private record Fixture(Object holder, Method players, Method packet) {
        Object players(Provider provider, Pos pos, boolean boundary) throws Exception {
            return players.invoke(holder, provider, pos, boundary);
        }
        Object packet(Packet original) throws Exception { return packet.invoke(holder, original); }
        Object heightAccessor() throws Exception { return holder.getClass().getField("levelHeightAccessor").get(holder); }
    }

    private static Fixture fixture(LevelHeightAccessor storage) throws Exception {
        ClassNode source = new ClassNode();
        try (var input = IplChunkHolderOwnerTest.class.getResourceAsStream("/" + MIXIN + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(source, 0);
        }
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "ipl/sable/dim/ChunkHolderOwnerFixture";
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "levelHeightAccessor", Type.getDescriptor(LevelHeightAccessor.class), null, null));
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        for (String name : List.of("redirectGetPlayers", "modifyPacket")) {
            MethodNode method = source.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            method.access = Opcodes.ACC_PUBLIC;
            node.methods.add(method);
        }
        var names = new HashMap<String, String>();
        names.put(MIXIN, node.name);
        names.put("net/minecraft/world/level/Level", Type.getInternalName(World.class));
        names.put("net/minecraft/server/level/ServerLevel", Type.getInternalName(ServerWorld.class));
        names.put("net/minecraft/server/MinecraftServer", Type.getInternalName(Server.class));
        names.put("net/minecraft/resources/ResourceKey", Type.getInternalName(Dimension.class));
        names.put("net/minecraft/server/level/ServerPlayer", Type.getInternalName(Player.class));
        names.put("net/minecraft/world/level/ChunkPos", Type.getInternalName(Pos.class));
        names.put("net/minecraft/network/protocol/Packet", Type.getInternalName(Packet.class));
        names.put("net/minecraft/server/level/ChunkHolder$PlayerProvider", Type.getInternalName(Provider.class));
        names.put("ipl/sable/SableBridge", Type.getInternalName(Routing.class));
        names.put("qouteall/imm_ptl/core/chunk_loading/ImmPtlChunkTracking", Type.getInternalName(Tracking.class));
        names.put("qouteall/imm_ptl/core/network/PacketRedirection", Type.getInternalName(Packets.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        class Loader extends ClassLoader {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        Class<?> type = new Loader().define(writer.toByteArray());
        Object holder = type.getConstructor().newInstance();
        type.getField("levelHeightAccessor").set(holder, storage);
        return new Fixture(holder, type.getMethod("redirectGetPlayers", Provider.class, Pos.class, boolean.class),
            type.getMethod("modifyPacket", Packet.class));
    }
}
