package ipl.sable.network;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** A client-world reset does not itself remove the server's retained tracking entry. */
public final class IplHostedTrackingResync {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-hosted-tracking");
    private static final Map<ServerGamePacketListenerImpl, Pending> CONNECTIONS = new WeakHashMap<>();

    private static final class Pending {
        final HostedTrackingReset.Server reset = new HostedTrackingReset.Server(20);
        final HostedTrackingReset.Replay<UUID> replays = new HostedTrackingReset.Replay<>();
        boolean scheduled;
    }

    private IplHostedTrackingResync() {}

    public static final class RemoteCallables {
        private RemoteCallables() {}

        /** RPC supplies the authenticated sender; the client chooses no player or dimension. */
        public static void requestReset(ServerPlayer sender, long revision) {
            MinecraftServer server = sender.serverLevel().getServer();
            if (!server.isSameThread()) {
                server.execute(() -> requestReset(sender, revision));
                return;
            }
            if (server.getPlayerList().getPlayer(sender.getUUID()) != sender) return;
            var connection = sender.connection;
            Pending pending = CONNECTIONS.computeIfAbsent(connection, ignored -> new Pending());
            if (!pending.reset.request(revision) || pending.scheduled) return;
            pending.scheduled = true;
            UUID viewer = sender.getUUID();

            // One task per connection, including during the cooldown. A rapid second
            // world reset replaces the pending revision instead of being rate-dropped.
            ServerTaskList.of(server).addTask(() -> {
                ServerPlayer current = server.getPlayerList().getPlayer(viewer);
                if (current == null || current.connection != connection) {
                    CONNECTIONS.remove(connection);
                    pending.scheduled = false;
                    return true;
                }
                long requested = pending.reset.takeRequest(server.getTickCount());
                if (requested == 0) return false;
                rememberHostedReplays(server, viewer, pending);
                pending.scheduled = false;
                LOG.info("[IPL-HOSTED-RESET] {} revision={} pending={} hosted allocation replays",
                    viewer, requested, pending.replays.size());
                return true;
            });
        }
    }

    private static void rememberHostedReplays(MinecraftServer server, UUID viewer, Pending pending) {
        var tracked = new ArrayList<UUID>();
        var hosting = SableSubLevelDimension.getSableSubLevelsOrNull(server);
        var container = hosting == null ? null : SubLevelContainer.getContainer(hosting);
        if (container != null) {
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (!subLevel.isRemoved() && subLevel instanceof ServerSubLevel serverSubLevel
                    && serverSubLevel.getTrackingPlayers().contains(viewer)) {
                    tracked.add(subLevel.getUniqueId());
                }
            }
        }
        pending.replays.reset(tracked);
        // Preserve server tracking: an in-flight allocation may have reached the
        // fresh client world. Normal removal must still be able to remove that
        // allocation if the viewer becomes ineligible before the next bootstrap.
    }

    /** Called after normal removals, before the hosting tracker bootstraps eligible viewers. */
    public static void pruneReplays(ServerLevel hosting, SubLevelContainer container) {
        if (!IplDimAgnostic.isHostingLevel(hosting)) return;
        MinecraftServer server = hosting.getServer();
        var iterator = CONNECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var connection = entry.getKey();
            ServerPlayer viewer = connection.player;
            if (viewer.serverLevel().getServer() != server) continue;
            if (server.getPlayerList().getPlayer(viewer.getUUID()) != viewer
                || viewer.connection != connection) {
                iterator.remove();
                continue;
            }
            entry.getValue().replays.retainTracked(id -> {
                SubLevel ship = container.getSubLevel(id);
                return ship instanceof ServerSubLevel serverShip && !ship.isRemoved()
                    && serverShip.getTrackingPlayers().contains(viewer.getUUID());
            });
        }
    }

    public static boolean needsReplay(ServerPlayer viewer, UUID ship) {
        Pending pending = CONNECTIONS.get(viewer.connection);
        return pending != null && pending.replays.needsReplay(ship);
    }

    /** Consume only after the full sync and its accompanying metadata have been sent. */
    public static void replayed(ServerPlayer viewer, UUID ship) {
        Pending pending = CONNECTIONS.get(viewer.connection);
        if (pending != null) pending.replays.synced(ship);
    }
}
