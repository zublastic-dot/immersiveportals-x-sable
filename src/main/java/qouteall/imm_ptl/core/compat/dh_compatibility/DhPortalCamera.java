package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Camera directions must use the same destination space as the rendered geometry. */
public final class DhPortalCamera {
    private DhPortalCamera() {}

    public static Vector3f lookDirection(Matrix4fc modelView) {
        Vector3f direction = new Matrix4f(modelView).invert()
            .transformDirection(new Vector3f(0, 0, -1));
        float lengthSquared = direction.lengthSquared();
        if (!direction.isFinite() || !Float.isFinite(lengthSquared) || lengthSquared < 1e-12f) return null;
        return direction.normalize();
    }
}
