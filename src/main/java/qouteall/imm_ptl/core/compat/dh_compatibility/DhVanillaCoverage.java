package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Conservative camera-centred radius inside the destination's available chunks. */
public final class DhVanillaCoverage {
    @FunctionalInterface public interface Loaded { boolean test(int x, int z); }
    private DhVanillaCoverage() {}

    public static int radius(int requested, double x, double z, Loaded loaded) {
        if (requested <= 1 || !Double.isFinite(x) || !Double.isFinite(z)) return requested;
        int cx = (int)Math.floor(x / 16), cz = (int)Math.floor(z / 16);
        double limit = requested * 16.0;
        double nearestSquared = limit * limit;
        for (int dx = -requested; dx <= requested; dx++) {
            for (int dz = -requested; dz <= requested; dz++) {
                int qx = cx + dx, qz = cz + dz;
                double ax = Math.max(Math.max(qx * 16.0 - x, x - (qx + 1.0) * 16), 0);
                double az = Math.max(Math.max(qz * 16.0 - z, z - (qz + 1.0) * 16), 0);
                double distanceSquared = ax * ax + az * az;
                if (distanceSquared < nearestSquared && !loaded.test(qx, qz)) nearestSquared = distanceSquared;
            }
        }
        // DH expects a positive chunk count, including when the centre is still loading.
        return Math.max(1, Math.min(requested, (int)Math.floor(Math.sqrt(nearestSquared) / 16)));
    }
}
