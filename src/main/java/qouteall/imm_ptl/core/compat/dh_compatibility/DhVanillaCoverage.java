package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Conservative camera-centred radius inside the destination's available chunks. */
public final class DhVanillaCoverage {
    @FunctionalInterface public interface Loaded { boolean test(int x, int z); }
    private DhVanillaCoverage() {}

    public static int radius(int requested, double x, double z, Loaded loaded) {
        if (requested <= 1 || !Double.isFinite(x) || !Double.isFinite(z)) return requested;
        return Math.max(1, Math.min(requested, (int)Math.floor(distance(requested, x, z, loaded) / 16)));
    }

    /** Exact horizontal distance; zero is meaningful when the camera chunk is not ready. */
    public static double blocks(int requested, double x, double z, Loaded loaded) {
        if (requested <= 0 || !Double.isFinite(x) || !Double.isFinite(z)) return 0;
        // A conservative upper bound keeps even scaled portals' render-thread work finite.
        return distance(Math.min(requested, 32), x, z, loaded);
    }

    private static double distance(int scanRadius, double x, double z, Loaded loaded) {
        int cx = (int)Math.floor(x / 16), cz = (int)Math.floor(z / 16);
        double limit = scanRadius * 16.0;
        double nearestSquared = limit * limit;
        // Visit the centre first so an unfinished nearby mesh stops the scan immediately.
        if (!loaded.test(cx, cz)) return 0;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                if (dx == 0 && dz == 0) continue;
                int qx = cx + dx, qz = cz + dz;
                double ax = Math.max(Math.max(qx * 16.0 - x, x - (qx + 1.0) * 16), 0);
                double az = Math.max(Math.max(qz * 16.0 - z, z - (qz + 1.0) * 16), 0);
                double distanceSquared = ax * ax + az * az;
                if (distanceSquared < nearestSquared && !loaded.test(qx, qz)) nearestSquared = distanceSquared;
            }
        }
        return Math.sqrt(nearestSquared);
    }
}
