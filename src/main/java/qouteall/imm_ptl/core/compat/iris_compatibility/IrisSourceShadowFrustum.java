package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.FrustumHolder;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy;

/** A source snapshot is consumed after the receiver rotates, so its casters cannot be view-culled. */
public final class IrisSourceShadowFrustum {
    private IrisSourceShadowFrustum() {}
    public static FrustumHolder select(FrustumHolder nativeFrustum, float halfPlane, float multiplier,
                                       int userShadowChunks, int effectiveChunks) {
        if (!PortalSourceRefreshPolicy.isRendering()) return nativeFrustum;
        double requested = multiplier < 0 ? userShadowChunks * 16.0 : halfPlane * (double)multiplier;
        double distance = Math.min(requested, effectiveChunks * 16.0);
        // Preserve native disabled/unsupported branches, including its CullEverythingFrustum.
        if (!Double.isFinite(distance) || distance <= 0 || distance > 8192) return nativeFrustum;
        return new FrustumHolder().setInfo(new BoxCullingFrustum(new BoxCuller(distance)),
            "Auxiliary source: " + distance + " blocks", "Finite source casters independent of receiver view");
    }
}
