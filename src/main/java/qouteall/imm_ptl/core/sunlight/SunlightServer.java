package qouteall.imm_ptl.core.sunlight;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import static net.minecraft.commands.Commands.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Server-thread authority. This class deliberately never links to Minecraft or Iris client classes. */
public final class SunlightServer {
    public static final int MAX_PORTAL_CANDIDATES = 32;
    public static final int MAX_READS_PER_TICK = 131072;
    public static final String RPC = SunlightServer.class.getName() + ".RemoteCallables.";
    public record Exposure(double strength, String path, String reason, int reads, int portals) {}
    public interface ClientSink { void accept(long revision, SunlightProfile profile); }
    private static final Map<MinecraftServer, State> SERVERS = new WeakHashMap<>();
    private static ClientSink clientSink;
    private static boolean initialized;
    private static final class State {
        final SunlightSavedData data;
        final Map<java.util.UUID, Integer> imports = new HashMap<>();
        int tick = Integer.MIN_VALUE, reads;
        State(MinecraftServer server) { data = SunlightSavedData.get(server); }
        int remaining(int now) {
            if (tick != now) { tick = now; reads = 0; }
            return Math.max(0, MAX_READS_PER_TICK - reads);
        }
    }
    private SunlightServer() {}
    public static void setClientSink(ClientSink sink) { clientSink = sink; }
    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) sync(player);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) sync(player);
        });
        // Native death/respawn can replace ServerPlayer without a dimension-change event.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) sync(player);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                State s = SERVERS.get(player.server);
                if (s != null) s.imports.remove(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> SERVERS.remove(e.getServer()));
    }
    private static State state(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Sunlight authority requires the server thread");
        return SERVERS.computeIfAbsent(server, State::new);
    }
    public static SunlightProfile profile(MinecraftServer server) { return state(server).data.profile(); }
    public static boolean enabled(ServerLevel level) { return profile(level.getServer()).enabled(); }
    private static void sync(ServerPlayer player) {
        var data = state(player.server).data;
        var p = data.profile();
        McRemoteProcedureCall.tellClientToInvoke(player, RPC + "accept", data.revision(), p.enabled(), p.pathRotationDegrees(), p.clock().name());
    }
    private static void change(MinecraftServer server, SunlightProfile value) {
        if (state(server).data.update(value))
            for (ServerPlayer player : server.getPlayerList().getPlayers()) sync(player);
    }
    private static String description(MinecraftServer server) {
        var d = state(server).data;
        return "IP sunlight (EXPERIMENTAL, per-world opt-in): " + d.profile() + ", revision=" + d.revision()
            + "; source=minecraft:overworld; one portal hop; reads/query=" + SunlightExposure.MAX_READS
            + "; reads/tick=" + MAX_READS_PER_TICK;
    }
    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(description(source.getServer())), false);
        return 1;
    }
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("imm_ptl_sunlight").requires(s -> s.hasPermission(2))
            .executes(c -> report(c.getSource()))
            .then(literal("status").executes(c -> report(c.getSource())))
            .then(literal("enable").executes(c -> {
                change(c.getSource().getServer(), profile(c.getSource().getServer()).withEnabled(true));
                return report(c.getSource());
            }))
            .then(literal("disable").executes(c -> {
                change(c.getSource().getServer(), profile(c.getSource().getServer()).withEnabled(false));
                return report(c.getSource());
            }))
            .then(literal("profile").then(argument("rotation", DoubleArgumentType.doubleArg(-180, 180))
                .then(argument("clock", StringArgumentType.word()).suggests((c, b) -> {
                    b.suggest("SUN_ANGLE"); b.suggest("WORLD_TIME"); return b.buildFuture();
                }).executes(c -> {
                    try {
                        var value = new SunlightProfile(true, DoubleArgumentType.getDouble(c, "rotation"),
                            SunlightProfile.Clock.valueOf(StringArgumentType.getString(c, "clock").toUpperCase(Locale.ROOT)));
                        change(c.getSource().getServer(), value);
                        return report(c.getSource());
                    } catch (IllegalArgumentException invalid) {
                        c.getSource().sendFailure(Component.literal("Clock must be SUN_ANGLE or WORLD_TIME; rotation must be finite."));
                        return 0;
                    }
                }))))
            .then(literal("probe").executes(c -> {
                Exposure result = query(c.getSource().getLevel(), c.getSource().getPosition());
                c.getSource().sendSuccess(() -> Component.literal("IP experimental sunlight exposure: " + result), false);
                return result.strength > 0 ? 1 : 0;
            })));
    }
    public static final class RemoteCallables {
        private RemoteCallables() {}
        /** Sender is supplied by the authenticated RPC transport, never accepted from the payload. */
        public static void publish(ServerPlayer player, double rotation, String clock) {
            if (player == null || !player.server.isSameThread()
                || player.server.getPlayerList().getPlayer(player.getUUID()) != player || !player.hasPermissions(2)) return;
            State s = state(player.server);
            int now = player.server.getTickCount();
            Integer previous = s.imports.get(player.getUUID());
            if (previous != null && now - previous >= 0 && now - previous < 20) return;
            s.imports.put(player.getUUID(), now);
            try {
                change(player.server, imported(true, rotation, clock));
                sync(player);
                player.sendSystemMessage(Component.literal(description(player.server)));
            } catch (IllegalArgumentException invalid) {
                player.sendSystemMessage(Component.literal("Rejected invalid sunlight profile: " + invalid.getMessage()));
            }
        }
        public static void accept(long revision, boolean enabled, double rotation, String clock) {
            ClientSink sink = clientSink;
            if (sink == null || revision < 1) return;
            try { sink.accept(revision, imported(enabled, rotation, clock)); }
            catch (IllegalArgumentException invalid) { /* malformed server profile cannot activate client overrides */ }
        }
    }
    static SunlightProfile imported(boolean enabled, double rotation, String clock) {
        if (clock == null || clock.length() > 16) throw new IllegalArgumentException("Invalid clock");
        return new SunlightProfile(enabled, rotation, SunlightProfile.Clock.valueOf(clock));
    }

    /** Called only from the existing mob sun-burn decision; callers retain their armor/fire rules. */
    public static boolean burnTick(Mob mob, ServerLevel world) {
        if (mob.isInWaterRainOrBubble() || mob.isInPowderSnow || mob.wasInPowderSnow) return false;
        float draw = mob.getRandom().nextFloat();
        // Match vanilla's one random draw, avoiding geometry work on ticks that cannot possibly ignite.
        if (!SunlightExposure.burnTick(1, draw, false, false, false)) return false;
        return SunlightExposure.burnTick(query(world, mob.getEyePosition()).strength, draw, false, false, false);
    }

    public static Exposure query(ServerLevel world, Vec3 eye) {
        MinecraftServer server = world.getServer();
        State s = state(server);
        if (!s.data.profile().enabled()) return new Exposure(0, "none", "disabled", 0, 0);
        int allowance = Math.min(SunlightExposure.MAX_READS, s.remaining(server.getTickCount()));
        if (allowance == 0) return new Exposure(0, "none", "tick_budget", 0, 0);
        var budget = new SunlightExposure.Budget(allowance);
        ServerLevel source = server.overworld();
        var sun = s.data.profile().sample(source.getDayTime());
        var sky = new SunlightExposure.Sky(reader(source, null), source.getMaxBuildHeight(), sun,
            SunlightExposure.weatherStrength(source.getRainLevel(1), source.getThunderLevel(1)));
        if (!sun.solarActive()) return new Exposure(0, "none", "night", 0, 0);
        double direct = world == source ? SunlightExposure.direct(sky, eye, budget) : 0;
        if (direct > 0) {
            s.reads += budget.reads();
            return new Exposure(direct, "direct", "sunlit", budget.reads(), 0);
        }
        int[] examined = {0};
        AABB box = new AABB(eye, eye).inflate(SunlightExposure.MAX_PORTAL_DISTANCE);
        Exposure portal = McHelper.traverseEntitiesByApproximateRegion(Portal.class, world, box, 16, p -> {
            if (examined[0] >= MAX_PORTAL_CANDIDATES || budget.exhausted())
                return new Exposure(0, "none", "query_budget", budget.reads(), Math.min(examined[0], MAX_PORTAL_CANDIDATES));
            examined[0]++;
            if (!eligible(p) || !p.getBoundingBox().intersects(box) || !Level.OVERWORLD.equals(p.dimensionTo)) return null;
            var aperture = new SunlightExposure.Aperture(p.getOriginPos(), p.getNormal(), p.getAxisW(), p.getAxisH(),
                p.getWidth(), p.getHeight(), p::transformPoint, p::inverseTransformLocalVecNonScale, sky);
            double strength = SunlightExposure.through(reader(world, p), eye, aperture, budget);
            return strength > 0 ? new Exposure(strength, p.getUUID().toString(), "sunlit", budget.reads(), examined[0]) : null;
        });
        s.reads += budget.reads();
        return portal != null ? portal : new Exposure(0, "none", budget.unknown() ? "unknown_or_budget" : "occluded",
            budget.reads(), examined[0]);
    }
    private static boolean eligible(Portal p) {
        return !p.isRemoved() && !p.isGlobalPortal && !(p instanceof Mirror) && p.specificPlayerId == null
            && p.isVisible() && !p.isFuseView() && p.isPortalValid() && p.getPortalShape() instanceof RectangularPortalShape
            && Math.abs(p.getScaling() - 1) < 1e-6 && p.getThickness() == 0
            && p.getWidth() > 0 && p.getHeight() > 0 && p.getWidth() <= 30 && p.getHeight() <= 30;
    }
    /** Cache only within one query. A missing chunk remains UNKNOWN and is never requested. */
    private static World reader(ServerLevel world, Portal aperture) {
        Map<Long, LevelChunk> chunks = new HashMap<>();
        return pos -> {
            BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
            if (world.isOutsideBuildHeight(at)) return Cell.UNKNOWN;
            long key = net.minecraft.world.level.ChunkPos.asLong(pos.x() >> 4, pos.z() >> 4);
            if (!chunks.containsKey(key)) chunks.put(key, world.getChunkSource().getChunkNow(pos.x() >> 4, pos.z() >> 4));
            LevelChunk chunk = chunks.get(key);
            if (chunk == null) return Cell.UNKNOWN;
            var block = chunk.getBlockState(at);
            if (aperture != null && block.getBlock() instanceof PortalPlaceholderBlock) {
                Vec3 delta = Vec3.atCenterOf(at).subtract(aperture.getOriginPos());
                Vec3 normal = aperture.getNormal();
                Direction.Axis axis = block.getValue(PortalPlaceholderBlock.AXIS);
                double axial = axis == Direction.Axis.X ? Math.abs(normal.x) : axis == Direction.Axis.Y ? Math.abs(normal.y) : Math.abs(normal.z);
                if (axial > 1 - 1e-6 && Math.abs(delta.dot(normal)) <= .501
                    && Math.abs(delta.dot(aperture.getAxisW())) < aperture.getWidth() / 2 + .501
                    && Math.abs(delta.dot(aperture.getAxisH())) < aperture.getHeight() / 2 + .501) return Cell.OPEN;
            }
            return block.getLightBlock(world, at) >= 15 ? Cell.CLOSED : Cell.OPEN;
        };
    }
}
