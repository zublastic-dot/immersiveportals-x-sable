package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalShaderDepthTest {
    private float near(int chunks) {
        double aspect=16.0/9.0, tangent=Math.tan(Math.toRadians(70)/2);
        return (float)(chunks*16*.2/Math.sqrt(1+tangent*tangent*(aspect*aspect+1)));
    }
    private Matrix4f projection(int chunks) {
        return new Matrix4f().setPerspective((float)Math.toRadians(70),16f/9f,near(chunks),4096);
    }
    private float reconstruct(Matrix4f drawn,Matrix4f inverse,float distance) {
        var clip=new Vector4f(0,0,-distance,1).mul(drawn);clip.div(clip.w);
        var view=clip.mul(inverse);return -view.z/view.w;
    }
    @Test void passLocalNearPlaneChangeBreaksIrisPerFrameDepthReconstruction() {
        // DH's event supplies per-pass nearClipPlane to Iris' draw matrix; Iris'
        // common dhProjectionInverse is independently cached PER_FRAME. .52
        // incorrectly changed only the pass distance 7 -> 1 when coverage was0.
        var inverseMain=projection(7).invert(new Matrix4f());
        assertEquals(60,reconstruct(projection(7),inverseMain,60),.002);
        float wrong=reconstruct(projection(1),inverseMain,60);
        assertTrue(wrong>300,"A nearby cave reconstructs hundreds of blocks away and receives excessive fog: "+wrong);
        assertEquals(60,reconstruct(projection(1),projection(1).invert(new Matrix4f()),60),.002);
        // .53 leaves the native draw/reconstruction pair identical. The alpha
        // coverage bound can still move independently from44.8 to below31.
        assertTrue(DhPortalShaderCoverage.fadeEnd(112,30.2)<31);
        assertEquals(60,reconstruct(projection(7),inverseMain,60),.002);
    }
}
