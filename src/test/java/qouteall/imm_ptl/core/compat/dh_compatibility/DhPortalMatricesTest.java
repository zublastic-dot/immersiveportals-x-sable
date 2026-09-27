package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalMatricesTest {
    private static DhMat4f dh(Matrix4f matrix) {
        DhMat4f result = new DhMat4f();
        result.set(matrix);
        return result;
    }

    private static Matrix4f joml(DhApiMat4f matrix) {
        float[] values = new float[16];
        matrix.putValuesInArray(values);
        return new Matrix4f().setTransposed(values);
    }

    private static Vector4f reconstruct(Matrix4f inverse, Vector4f ndc) {
        Vector4f result = inverse.transform(new Vector4f(ndc));
        return result.div(result.w);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void portalDepthReconstructsTheSameCloudAndTerrainDistances(boolean reverse) {
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), 1.6f,
            reverse ? 4096f : .1f, reverse ? .1f : 4096f, reverse);
        Matrix4f view = new Matrix4f().rotateY(.3f).rotateX(-.15f);
        DhApiRenderParam params = new DhApiRenderParam();
        params.update(EDhApiRenderPass.OPAQUE, .5f, .1f, 4096f,
            dh(projection), dh(view), dh(projection), dh(view), -64, null);
        Matrix4f oldInverse = joml(params.dhInverseMvmProjectionMatrix);
        Matrix4f clipped = DhPortalProjection.clip(projection, view,
            new Vector4f(.2f, .1f, -1, -30), reverse);
        assertNotNull(clipped);

        DhPortalMatrices.applyProjection(params, dh(clipped));
        Matrix4f draw = new Matrix4f(clipped).mul(view);
        // DH's fog path inverts dhMvmProjMatrix itself. Other consumers use the
        // precomputed inverse, and API events receive a refreshed parameter copy.
        Matrix4f fogInverse = joml(params.dhMvmProjMatrix).invert();
        Matrix4f cachedInverse = joml(params.dhInverseMvmProjectionMatrix);
        DhApiRenderParam apiCopy = new DhApiRenderParam();
        apiCopy.update(params);
        Matrix4f eventInverse = joml(apiCopy.dhInverseMvmProjectionMatrix);
        for (Vector4f world : new Vector4f[]{
            new Vector4f(180, 420, -800, 1), new Vector4f(750, 950, -2800, 1)
        }) {
            Vector4f ndc = draw.transform(new Vector4f(world));
            ndc.div(ndc.w);
            assertTrue(reconstruct(oldInverse, ndc).distance(world) > 100,
                "The .8 stale-matrix behavior must fail this realistic depth round-trip");
            for (Matrix4f inverse : new Matrix4f[]{fogInverse, cachedInverse, eventInverse}) {
                assertTrue(reconstruct(inverse, ndc).distance(world) < 1,
                    "Fog/depth consumers must recover the rendered camera-relative position");
            }
        }
    }
}
