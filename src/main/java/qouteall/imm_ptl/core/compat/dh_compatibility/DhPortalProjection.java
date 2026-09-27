package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Oblique near plane: works with DH's forward-Z and reverse-Z projections. */
public final class DhPortalProjection {
    private DhPortalProjection() {}

    public static Matrix4f clip(Matrix4fc projection, Matrix4fc modelView,
                                Vector4fc cameraRelativePlane, boolean reverseZ) {
        Vector4f plane = new Matrix4f(modelView).invert().transpose()
            .transform(new Vector4f(cameraRelativePlane));
        float normalLengthSquared = plane.x * plane.x + plane.y * plane.y + plane.z * plane.z;
        if (!Float.isFinite(normalLengthSquared) || normalLengthSquared < 1e-12f) return null;
        Vector4f corner = new Matrix4f(projection).invert().transform(new Vector4f(
            Math.copySign(1f, plane.x), Math.copySign(1f, plane.y), reverseZ ? 0f : 1f, 1f));
        float denominator = plane.dot(corner);
        // A grazing/degenerate plane cannot safely define an oblique frustum.
        if (!Float.isFinite(denominator) || denominator <= 1e-6f) return null;
        plane.mul((reverseZ ? 1f : 2f) / denominator);
        Matrix4f result = new Matrix4f(projection);
        Vector4f rowW = result.getRow(3, new Vector4f());
        result.setRow(2, reverseZ ? rowW.sub(plane) : plane.sub(rowW));
        return result.isFinite() ? result : null;
    }
}
