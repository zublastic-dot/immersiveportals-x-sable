package qouteall.imm_ptl.core.render.impostor;

import de.nick1st.imm_ptl.events.DimensionEvents;
import de.nick1st.imm_ptl.events.PortalDisposeEvent;
import de.nick1st.imm_ptl.events.ServerCleanupEvent;
import ipl.sable.transit.IplShipPortalAnchor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Small authoritative leases for already observed apertures. This never creates a client world,
 * requests a chunk, adds a ticket or retains an entity/player. A dormant lease asserts only the
 * last known aperture/link, not that its cached destination picture is current.
 */
public final class PortalImpostorSync {
    public static final int MAX_PER_PLAYER = 16;
    public static final int MAX_TOTAL = 1024;
    public static final long SERVER_LEASE_MILLIS = 10_000;
    public static final long CLIENT_LEASE_MILLIS = 5_000;
    public static final double MAX_DISTANCE = 2048;
    private static final String RPC = PortalImpostorSync.class.getName() + ".RemoteCallables.";
    private static final Map<MinecraftServer, ServerState> SERVERS = new WeakHashMap<>();
    private static boolean registered;
    private static ClientSink clientSink;

    private PortalImpostorSync() {}

    public interface ClientSink {
        void updated(UUID token, long generation, PortalImpostorMetadata value, boolean dormant, long leaseMillis);
        void invalidated(UUID token, long generation, String reason);
    }

    /** The RPC dispatcher invokes S2C handlers on the client main/render thread. */
    public static void setClientSink(ClientSink sink) { clientSink = sink; }

    public static void initServer() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PortalDisposeEvent.class, event -> onRemoved(event.portal));
        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, event -> SERVERS.remove(event.server));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> SERVERS.remove(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ServerState state = SERVERS.get(player.server);
                if (state != null) {
                    state.players.remove(player.getUUID());
                    state.rates.remove(player.getUUID());
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(DimensionEvents.BeforeRemovingDimensionEvent.class, event -> {
            ServerState state = SERVERS.get(event.getServer());
            if (state == null) return;
            String dimension = event.dimension.dimension().location().toString();
            for (var owner : state.players.entrySet()) {
                ServerPlayer player = event.getServer().getPlayerList().getPlayer(owner.getKey());
                Iterator<PortalImpostorLease> iterator = owner.getValue().values().iterator();
                while (iterator.hasNext()) {
                    PortalImpostorLease lease = iterator.next();
                    if (lease.metadata.sourceDimension().equals(dimension) || lease.metadata.destinationDimension().equals(dimension)) {
                        iterator.remove();
                        invalidated(player, lease.token, lease.generation, "dimension_removed");
                    }
                }
            }
        });
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    static final class Rate {
        long since;
        int count;
        boolean allow(long time) {
            // nanoTime has an arbitrary origin, which is allowed to be negative.
            if (count == 0 || time - since >= 1000) { since = time; count = 0; }
            if (count >= 64) return false;
            count++;
            return true;
        }
    }

    private static final class ServerState {
        final Map<UUID, LinkedHashMap<UUID, PortalImpostorLease>> players = new HashMap<>();
        final Map<UUID, Rate> rates = new HashMap<>();
        long generation;
        boolean allow(ServerPlayer player) {
            return player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && rates.computeIfAbsent(player.getUUID(), id -> new Rate()).allow(now());
        }
        int size() { return players.values().stream().mapToInt(Map::size).sum(); }
    }

    private static ResourceKey<Level> dimension(String id) {
        if (id == null || id.length() > 128) return null;
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static ServerLevel source(MinecraftServer server, PortalImpostorMetadata metadata) {
        ResourceKey<Level> key = dimension(metadata.sourceDimension());
        return key == null ? null : server.getLevel(key);
    }

    /** Admission requires an actual loaded, sent source chunk and a nearby source-world player. */
    private static boolean canObserve(ServerPlayer player, Portal portal) {
        if (player.level() != portal.level() || !eligible(portal) || !portal.broadcastToPlayer(player)) return false;
        double range = McHelper.getPlayerLoadDistance(player) * 16.0 + 16;
        if (portal.getOriginPos().distanceToSqr(player.position()) > range * range) return false;
        var position = portal.chunkPosition();
        var records = ImmPtlChunkTracking.getWatchRecordForChunk(portal.getOriginDim(), position.x, position.z);
        var record = records == null ? null : records.get(player);
        return record != null && record.isValid && record.isLoadedToPlayer;
    }

    private static boolean eligible(Portal portal) {
        return !portal.isRemoved() && !portal.isGlobalPortal && !(portal instanceof Mirror)
            && portal.isVisible() && !portal.isFuseView() && portal.isPortalValid()
            && portal.getPortalShape() instanceof RectangularPortalShape && portal.getThickness() == 0;
    }

    private static String validate(ServerPlayer player, PortalImpostorLease lease, long time) {
        if (!lease.isAlive(time)) return "lease_expired";
        PortalImpostorMetadata metadata = lease.metadata;
        if (!player.level().dimension().location().toString().equals(metadata.sourceDimension())) return "source_world_changed";
        ServerLevel level = source(player.server, metadata);
        ResourceKey<Level> destination = dimension(metadata.destinationDimension());
        if (level == null || destination == null || player.server.getLevel(destination) == null) return "dimension_unavailable";
        // getEntity only observes the existing entity index. It cannot load a chunk.
        Entity entity = level.getEntity(metadata.portalId());
        if (entity instanceof Portal portal && eligible(portal) && portal.broadcastToPlayer(player)) {
            try {
                if (!lease.observeLoaded(PortalImpostorMetadata.fromPortal(portal), time)) return "link_changed";
            } catch (RuntimeException invalid) { return "invalid_metadata"; }
        } else if (entity != null || !lease.missingIsExpected(time)) {
            return "source_unavailable";
        }
        metadata = lease.metadata;
        if (!lease.attachmentsMatch(IplShipPortalAnchor.attachmentFingerprint(metadata.sourceAnchor()),
            IplShipPortalAnchor.attachmentFingerprint(metadata.destinationAnchor()))) return "attachment_changed";
        return player.position().distanceToSqr(metadata.origin()) <= MAX_DISTANCE * MAX_DISTANCE ? null : "out_of_range";
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        ServerState state = SERVERS.get(server);
        if (state == null) return;
        long time = now();
        var owners = state.players.entrySet().iterator();
        while (owners.hasNext()) {
            var owner = owners.next();
            ServerPlayer player = server.getPlayerList().getPlayer(owner.getKey());
            if (player == null) { owners.remove(); state.rates.remove(owner.getKey()); continue; }
            var leases = owner.getValue().values().iterator();
            while (leases.hasNext()) {
                PortalImpostorLease lease = leases.next();
                String reason = validate(player, lease, time);
                if (reason != null) {
                    leases.remove(); invalidated(player, lease.token, lease.generation, reason);
                } else accepted(player, lease);
            }
            if (owner.getValue().isEmpty()) owners.remove();
        }
        state.rates.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    private static void onRemoved(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        ServerState state = SERVERS.get(level.getServer());
        if (state == null) return;
        String dimension = level.dimension().location().toString();
        UUID id = portal.getUUID();
        boolean unload = portal.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK;
        for (var owner : state.players.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner.getKey());
            var iterator = owner.getValue().values().iterator();
            while (iterator.hasNext()) {
                PortalImpostorLease lease = iterator.next();
                if (!lease.metadata.references(dimension, id)) continue;
                if (unload) {
                    // Unloading the other endpoint is not deletion. A source disappearance needs its own proof.
                    if (!lease.metadata.portalId().equals(id) || !lease.metadata.sourceDimension().equals(dimension)) continue;
                    try {
                        if (lease.observeChunkUnload(PortalImpostorMetadata.fromPortal(portal), now())) continue;
                    } catch (RuntimeException ignored) { /* fail closed below */ }
                }
                iterator.remove();
                invalidated(player, lease.token, lease.generation, unload ? "link_changed" : "portal_removed");
            }
        }
    }

    private static void accepted(ServerPlayer player, PortalImpostorLease lease) {
        McRemoteProcedureCall.tellClientToInvoke(player, RPC + "accept", lease.token, lease.generation, lease.metadata.toJson(), lease.dormant());
    }

    private static void invalidated(ServerPlayer player, UUID token, long generation, String reason) {
        if (player != null && token != null) McRemoteProcedureCall.tellClientToInvoke(player, RPC + "invalidate", token, generation, reason);
    }

    public static final class RemoteCallables {
        private RemoteCallables() {}

        public static void subscribe(ServerPlayer player, String sourceDim, UUID portalId, UUID token) {
            ServerState state = SERVERS.computeIfAbsent(player.server, server -> new ServerState());
            if (!state.allow(player) || token == null || portalId == null) return;
            ResourceKey<Level> key = dimension(sourceDim);
            ServerLevel level = key == null ? null : player.server.getLevel(key);
            Entity entity = level == null ? null : level.getEntity(portalId);
            if (!(entity instanceof Portal portal) || !canObserve(player, portal)) {
                invalidated(player, token, 0, "not_observed"); return;
            }
            var leases = state.players.computeIfAbsent(player.getUUID(), id -> new LinkedHashMap<>());
            // Reusing a token cannot replace its identity or silently advance a capture generation.
            PortalImpostorLease existing = leases.get(token);
            if (existing != null) {
                if (!existing.metadata.portalId().equals(portalId) || !existing.metadata.sourceDimension().equals(sourceDim)) {
                    invalidated(player, token, existing.generation, "token_reused"); leases.remove(token); return;
                }
                renew(player, token); return;
            }
            if (leases.size() >= MAX_PER_PLAYER || state.size() >= MAX_TOTAL) {
                invalidated(player, token, 0, "capacity"); return;
            }
            try {
                PortalImpostorMetadata metadata = PortalImpostorMetadata.fromPortal(portal);
                PortalImpostorLease lease = new PortalImpostorLease(token, ++state.generation, metadata, now(),
                    IplShipPortalAnchor.attachmentFingerprint(metadata.sourceAnchor()),
                    IplShipPortalAnchor.attachmentFingerprint(metadata.destinationAnchor()));
                leases.put(token, lease);
                accepted(player, lease);
            } catch (RuntimeException invalid) { invalidated(player, token, 0, "invalid_metadata"); }
        }

        public static void renew(ServerPlayer player, UUID token) {
            ServerState state = SERVERS.get(player.server);
            if (state == null || !state.allow(player) || token == null) return;
            var leases = state.players.get(player.getUUID());
            PortalImpostorLease lease = leases == null ? null : leases.get(token);
            if (lease == null) { invalidated(player, token, 0, "unknown_lease"); return; }
            long time = now();
            String reason = validate(player, lease, time);
            if (reason != null || !lease.renew(time)) {
                leases.remove(token); invalidated(player, token, lease.generation, reason == null ? "lease_expired" : reason); return;
            }
            // The periodic heartbeat sends geometry; renewal does not need another packet.
        }

        public static void unsubscribe(ServerPlayer player, UUID token) {
            ServerState state = SERVERS.get(player.server);
            if (state == null || !state.allow(player) || token == null) return;
            var leases = state.players.get(player.getUUID());
            if (leases != null) leases.remove(token);
        }

        public static void accept(UUID token, long generation, String json, boolean dormant) {
            ClientSink sink = clientSink;
            if (sink == null || token == null || generation <= 0) return;
            try { sink.updated(token, generation, PortalImpostorMetadata.fromJson(json), dormant, CLIENT_LEASE_MILLIS); }
            catch (IllegalArgumentException invalid) { sink.invalidated(token, generation, "invalid_metadata"); }
        }

        public static void invalidate(UUID token, long generation, String reason) {
            ClientSink sink = clientSink;
            if (sink != null && token != null && generation >= 0) sink.invalidated(token, generation,
                reason != null && reason.length() <= 64 ? reason : "invalidated");
        }
    }
}
