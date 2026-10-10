package ipl.sable.diagnostics.client;

import ipl.sable.diagnostics.IgnitionTraceWindow;
import ipl.sable.diagnostics.IplIgnitionTrace;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.UUID;

/** Fixed server-to-client diagnostic control using IP's existing authenticated connection. */
public final class IplIgnitionTraceRemoteCallables {
    static {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
            e -> IplIgnitionTrace.clear(IplIgnitionTrace.Side.CLIENT));
    }
    public static void arm(UUID token, UUID player, String dimension, int x, int y, int z,
        int radius, int attempts, int seconds) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.getUUID().equals(player)
            || !mc.player.level().dimension().location().toString().equals(dimension)) return;
        IplIgnitionTrace.arm(IplIgnitionTrace.Side.CLIENT, new IgnitionTraceWindow(token, player, dimension,
            x, y, z, radius, attempts, seconds, System::nanoTime));
    }
    public static void stop(UUID token) {
        var w = IplIgnitionTrace.window(IplIgnitionTrace.Side.CLIENT);
        if (w != null && w.token.equals(token)) IplIgnitionTrace.clear(IplIgnitionTrace.Side.CLIENT);
    }
}
