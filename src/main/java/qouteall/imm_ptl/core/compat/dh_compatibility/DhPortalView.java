package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

/** Immutable handoff from the render thread to DH's existing client tick timer. */
public record DhPortalView(IClientLevelWrapper level, double x, double y, double z, long seenNanos) {
    public static final long ACTIVE_NANOS = 2_000_000_000L;
    public static boolean isRecent(long seen, long now) {
        long elapsed = now - seen;
        return elapsed >= 0 && elapsed < ACTIVE_NANOS;
    }
    public boolean isRecent() { return isRecent(seenNanos, System.nanoTime()); }
}
