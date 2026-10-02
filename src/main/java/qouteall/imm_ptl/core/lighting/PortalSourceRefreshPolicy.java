package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Pure scheduling/pose boundary for observer-independent native source shadow renders. */
public final class PortalSourceRefreshPolicy {
    public static final long REFRESH_NANOS = 250_000_000L;
    public static final long RETRY_NANOS = 1_000_000_000L;
    public static final long MAX_PIXELS = 8_388_608;
    private static boolean rendering;
    private PortalSourceRefreshPolicy() {}

    /** Common, optional-mod-safe guard; all scopes belong to the render thread. */
    public static boolean isRendering() { return rendering; }
    public static Scope enter() {
        if (rendering) throw new IllegalStateException("Recursive source shadow refresh");
        rendering = true;
        return new Scope();
    }
    public static final class Scope implements AutoCloseable {
        private boolean closed;
        private Scope() {}
        @Override public void close() { if (!closed) { rendering = false; closed = true; } }
    }

    public static boolean due(long now, long captured, long nextAttempt) {
        return now >= nextAttempt
            && (captured < 0 || now < captured || now - captured >= REFRESH_NANOS);
    }

    public static boolean supportedSize(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    public static Vec3 sourcePoint(Vec3 point, Vec3 targetCenter, Vec3 sourceCenter,
                                   Vec3 x, Vec3 y, Vec3 z) {
        Vec3 relative = point.subtract(targetCenter);
        return sourceCenter.add(x.scale(relative.x)).add(y.scale(relative.y)).add(z.scale(relative.z));
    }

    /** Source coordinates must be rotated back into the receiver's native camera basis. */
    public static Matrix4f cameraTransform(Vec3 x, Vec3 y, Vec3 z) {
        return new Matrix4f().m00((float)x.x).m01((float)x.y).m02((float)x.z)
            .m10((float)y.x).m11((float)y.y).m12((float)y.z)
            .m20((float)z.x).m21((float)z.y).m22((float)z.z).transpose();
    }

    public static double distanceSquared(Vec3 point, PortalLightField.Pos min, PortalLightField.Pos max) {
        double x = Math.max(Math.max(min.x() - point.x, point.x - (max.x() + 1)), 0);
        double y = Math.max(Math.max(min.y() - point.y, point.y - (max.y() + 1)), 0);
        double z = Math.max(Math.max(min.z() - point.z, point.z - (max.z() + 1)), 0);
        return x*x + y*y + z*z;
    }
}
