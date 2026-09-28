package ipl.sable.network;

import java.util.function.BooleanSupplier;

/** Keeps assembly, rehome, and movement on the same ordered connection. */
public final class HostedMovementRouting {
    private HostedMovementRouting() {}

    public static boolean useUnorderedTransport(boolean sameDimension, boolean hostingAvailable,
                                                BooleanSupplier connected) {
        // Before rehome, a new ship still lives alongside the player. Sable's
        // local/UDP shortcut can overtake its TCP StartTracking, or arrive after
        // StopTracking. Hosted ships already require dimension-stamped TCP.
        return sameDimension && !hostingAvailable && connected.getAsBoolean();
    }
}
