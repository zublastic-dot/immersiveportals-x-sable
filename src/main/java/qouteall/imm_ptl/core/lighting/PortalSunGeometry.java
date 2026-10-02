package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.DQuaternion;

import static qouteall.imm_ptl.core.lighting.PortalLightField.Cell;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;
import static qouteall.imm_ptl.core.lighting.PortalLightField.World;

/** Pure aperture geometry and bounded visibility. Readers must not load missing world data. */
public final class PortalSunGeometry {
    public static final int MAX_CELL_READS = 4096;
    private static final double EPSILON = 1e-9;
    private static final double FRAME_TOLERANCE = 1e-6;

    private PortalSunGeometry() {}

    /** Distance is in blocks; u/v are signed distances along the supplied unit aperture axes. */
    public record Hit(double distance, double u, double v, Vec3 position) {}

    /**
     * The normal points from the aperture into the receiving room. The receiver must be
     * on that side and its ray must head back out through the aperture. Exact rectangle
     * edges are excluded: a grazing ray must not leak through the surrounding frame.
     */
    public static @Nullable Hit hit(Vec3 point, Vec3 towardSource, Vec3 inwardNormal,
                                    Vec3 center, Vec3 axisU, Vec3 axisV,
                                    double halfWidth, double halfHeight) {
        Vec3 direction = unit(towardSource);
        if (!finite(point) || !finite(center) || direction == null
            || !unitFrame(inwardNormal, axisU, axisV)
            || !Double.isFinite(halfWidth) || !Double.isFinite(halfHeight)
            || halfWidth <= 0 || halfHeight <= 0) return null;
        double side = point.subtract(center).dot(inwardNormal);
        double denominator = direction.dot(inwardNormal);
        if (!(side > EPSILON) || !(denominator < -EPSILON)) return null;
        double distance = -side / denominator;
        Vec3 position = point.add(direction.scale(distance));
        if (!Double.isFinite(distance) || !finite(position)) return null;
        Vec3 offset = position.subtract(center);
        double u = offset.dot(axisU), v = offset.dot(axisV);
        if (!(Math.abs(u) < halfWidth) || !(Math.abs(v) < halfHeight)) return null;
        return new Hit(distance, u, v, position);
    }

    /** Full quaternion rotation only, with normalized output; null denotes identity rotation. */
    public static @Nullable Vec3 rotateDirection(Vec3 direction, @Nullable DQuaternion rotation) {
        Vec3 normalized = unit(direction);
        if (normalized == null) return null;
        if (rotation == null) return normalized;
        double lengthSquared = rotation.dotProduct(rotation);
        if (!Double.isFinite(lengthSquared) || lengthSquared <= EPSILON * EPSILON) return null;
        return unit(rotation.multiply(1.0 / Math.sqrt(lengthSquared)).rotate(normalized));
    }

    /**
     * Tests the open segment (start,end). Callers bias a surface start into its adjacent
     * air cell. Exact edge/corner crossings conservatively inspect every touched voxel;
     * rays running along a voxel plane inspect both sides. UNKNOWN/null and exhaustion
     * fail closed. maxSteps is a cell-read budget, not an unbounded marching distance.
     */
    public static boolean clearSegment(World cells, Vec3 start, Vec3 end, int maxSteps) {
        if (cells == null || !finite(start) || !finite(end)
            || maxSteps < 1 || maxSteps > MAX_CELL_READS) return false;
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (!Double.isFinite(length) || length <= EPSILON) return false;
        Vec3 direction = delta.scale(1.0 / length);
        int[] step = {sign(direction.x), sign(direction.y), sign(direction.z)};
        double[] origin = {start.x, start.y, start.z};
        double[] velocity = {direction.x, direction.y, direction.z};
        int[] cell = new int[3];
        double[] next = new double[3], interval = new double[3];
        int stationaryBoundary = 0;
        for (int a = 0; a < 3; a++) {
            double floor = Math.floor(origin[a]);
            if (floor <= Integer.MIN_VALUE || floor >= Integer.MAX_VALUE) return false;
            cell[a] = (int) floor;
            if (step[a] < 0 && origin[a] == floor) cell[a]--;
            if (step[a] == 0) {
                next[a] = interval[a] = Double.POSITIVE_INFINITY;
                if (origin[a] == floor) stationaryBoundary |= 1 << a;
            } else {
                interval[a] = Math.abs(1.0 / velocity[a]);
                next[a] = ((step[a] > 0 ? cell[a] + 1.0 : cell[a]) - origin[a]) / velocity[a];
            }
        }
        var reader = new Reader(cells, maxSteps, stationaryBoundary);
        if (!reader.open(cell[0], cell[1], cell[2])) return false;
        // Every iteration reads at least one cell, so the reader budget also bounds this loop.
        while (reader.remaining > 0) {
            double distance = Math.min(next[0], Math.min(next[1], next[2]));
            if (reachesEnd(distance, length)) return true;
            int crossed = 0;
            for (int a = 0; a < 3; a++)
                if (Math.abs(next[a] - distance) <= EPSILON) crossed |= 1 << a;
            if (crossed == 0) return false;
            for (int subset = crossed; subset != 0; subset = (subset - 1) & crossed) {
                long x = (long) cell[0] + ((subset & 1) != 0 ? step[0] : 0);
                long y = (long) cell[1] + ((subset & 2) != 0 ? step[1] : 0);
                long z = (long) cell[2] + ((subset & 4) != 0 ? step[2] : 0);
                if (!reader.open(x, y, z)) return false;
            }
            for (int a = 0; a < 3; a++) if ((crossed & 1 << a) != 0) {
                cell[a] += step[a];
                next[a] += interval[a];
            }
        }
        // The last allowed cell may contain the remainder of the segment.
        return reachesEnd(Math.min(next[0], Math.min(next[1], next[2])), length);
    }

    /** Requires an upward clear ray all the way to the exclusive world build ceiling. */
    public static boolean clearToSky(World cells, Vec3 start, Vec3 towardSun,
                                     int maxBuildHeight, int maxSteps) {
        Vec3 direction = unit(towardSun);
        if (cells == null || !finite(start) || direction == null || direction.y <= EPSILON
            || maxSteps < 1 || maxSteps > MAX_CELL_READS) return false;
        if (start.y >= maxBuildHeight) return true;
        double distance = (maxBuildHeight - start.y) / direction.y;
        Vec3 end = start.add(direction.scale(distance));
        // Pin the exact endpoint to the ceiling to avoid reading a rounding-created voxel above it.
        return clearSegment(cells, start, new Vec3(end.x, maxBuildHeight, end.z), maxSteps);
    }

    private static final class Reader {
        final World cells;
        final int stationaryBoundary;
        int remaining;
        Reader(World cells, int remaining, int stationaryBoundary) {
            this.cells = cells; this.remaining = remaining; this.stationaryBoundary = stationaryBoundary;
        }
        boolean open(long x, long y, long z) {
            int subset = stationaryBoundary;
            while (true) {
                long sx = x - ((subset & 1) != 0 ? 1 : 0);
                long sy = y - ((subset & 2) != 0 ? 1 : 0);
                long sz = z - ((subset & 4) != 0 ? 1 : 0);
                if (remaining == 0 || sx < Integer.MIN_VALUE || sx > Integer.MAX_VALUE
                    || sy < Integer.MIN_VALUE || sy > Integer.MAX_VALUE
                    || sz < Integer.MIN_VALUE || sz > Integer.MAX_VALUE) return false;
                remaining--;
                if (cells.cell(new Pos((int) sx, (int) sy, (int) sz)) != Cell.OPEN) return false;
                if (subset == 0) return true;
                subset = (subset - 1) & stationaryBoundary;
            }
        }
    }

    private static boolean unitFrame(Vec3 normal, Vec3 u, Vec3 v) {
        return unitLength(normal) && unitLength(u) && unitLength(v)
            && Math.abs(normal.dot(u)) < FRAME_TOLERANCE
            && Math.abs(normal.dot(v)) < FRAME_TOLERANCE
            && Math.abs(u.dot(v)) < FRAME_TOLERANCE;
    }
    private static boolean reachesEnd(double distance, double length) {
        // Re-normalizing a diagonal segment can put its terminal crossing a few ulps
        // before its length. Do not interpret that rounding error as another voxel.
        return distance >= length || length - distance <= Math.max(EPSILON, 4 * Math.ulp(length));
    }
    private static boolean unitLength(Vec3 vector) {
        return finite(vector) && Math.abs(vector.lengthSqr() - 1) < FRAME_TOLERANCE;
    }
    private static @Nullable Vec3 unit(@Nullable Vec3 vector) {
        if (!finite(vector)) return null;
        double length = vector.length();
        return Double.isFinite(length) && length > EPSILON ? vector.scale(1.0 / length) : null;
    }
    private static boolean finite(@Nullable Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
    private static int sign(double value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }
}
