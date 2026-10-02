package qouteall.imm_ptl.core.render.impostor;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;

/** Homography between a rectangular aperture's local UVs and the current camera clip space. */
public final class PortalImpostorProjection {
    private final Matrix4f clipFromUv;
    private final boolean fullyVisible;

    private PortalImpostorProjection(Matrix4f clipFromUv, boolean fullyVisible) {
        this.clipFromUv = clipFromUv;
        this.fullyVisible = fullyVisible;
    }

    public static Optional<PortalImpostorProjection> create(
        Matrix4f modelView, Matrix4f projection, Vec3 originRelativeToCamera,
        Vec3 axisW, Vec3 axisH, double width, double height
    ) {
        if (modelView == null || projection == null || !modelView.isFinite() || !projection.isFinite()
            || !finite(originRelativeToCamera) || !finite(axisW) || !finite(axisH)
            || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
            || axisW.cross(axisH).lengthSqr() < 1e-12) return Optional.empty();
        Vec3 w = axisW.scale(width), h = axisH.scale(height);
        Vec3 corner = originRelativeToCamera.subtract(w.scale(.5)).subtract(h.scale(.5));
        Matrix4f plane = new Matrix4f().zero()
            .m00((float) w.x).m01((float) w.y).m02((float) w.z)
            .m10((float) h.x).m11((float) h.y).m12((float) h.z)
            .m30((float) corner.x).m31((float) corner.y).m32((float) corner.z).m33(1);
        Matrix4f clip = new Matrix4f(projection).mul(modelView).mul(plane);
        if (!clip.isFinite()) return Optional.empty();
        boolean visible = true;
        for (int v = 0; v <= 1; v++) for (int u = 0; u <= 1; u++) {
            Vector4f p = clip.transform(new Vector4f(u, v, 0, 1));
            visible &= p.w > 1e-6f && Math.abs(p.x) <= p.w && Math.abs(p.y) <= p.w
                && p.z >= -p.w && p.z <= p.w;
        }
        return Optional.of(new PortalImpostorProjection(clip, visible));
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    public Matrix4f clipFromUv() { return new Matrix4f(clipFromUv); }
    public boolean fullyVisible() { return fullyVisible; }
}
