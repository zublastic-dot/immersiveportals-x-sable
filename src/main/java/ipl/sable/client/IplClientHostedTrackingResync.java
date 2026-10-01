package ipl.sable.client;

import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import de.nick1st.imm_ptl.events.ClientExitEvent;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.network.HostedTrackingReset;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

/** Re-bootstrap hosted ships after vanilla dimension travel discards IP's client worlds. */
public final class IplClientHostedTrackingResync {
    private static final HostedTrackingReset.Client<Connection> RESET = new HostedTrackingReset.Client<>();

    private IplClientHostedTrackingResync() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> {
            var listener = Minecraft.getInstance().getConnection();
            RESET.onCleanup(listener == null ? null : listener.getConnection());
        });
        NeoForge.EVENT_BUS.addListener(ClientExitEvent.class, event -> RESET.clear());
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, event -> tick());
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        var listener = client.getConnection();
        Connection connection = listener == null ? null : listener.getConnection();
        boolean ready = listener != null && connection.isConnected()
            && client.level != null && client.player != null
            && client.player.connection == listener && client.player.level() == client.level;
        long revision = RESET.takeRequest(connection, ready);
        if (revision == 0 || !listener.levels().contains(SableSubLevelDimension.SUBLEVELS)) return;

        // This tick is after updateLevelInEngines completed. Sending at cleanup HEAD
        // can race the teardown and discard the very allocation being requested.
        McRemoteProcedureCallClient.tellServerToInvoke(
            "ipl.sable.network.IplHostedTrackingResync.RemoteCallables.requestReset", revision);
    }
}
