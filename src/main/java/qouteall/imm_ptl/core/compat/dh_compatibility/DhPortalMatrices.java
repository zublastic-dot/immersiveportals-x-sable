package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

/** Keep DH's depth consumers consistent with the projection used to draw LODs. */
public final class DhPortalMatrices {
    private DhPortalMatrices() {}

    public static void applyProjection(DhApiRenderParam params, DhApiMat4f projection) {
        params.dhProjectionMatrix.set(projection);
        // RenderParams.update has already cached these before our portal clipping hook.
        // Fog uses the combined matrix; depth reconstruction also exposes its inverse.
        params.dhMvmProjMatrix.set(params.dhProjectionMatrix);
        params.dhMvmProjMatrix.multiply(params.dhModelViewMatrix);
        params.dhInverseMvmProjectionMatrix.set(params.dhMvmProjMatrix);
        params.dhInverseMvmProjectionMatrix.invert();
    }
}
