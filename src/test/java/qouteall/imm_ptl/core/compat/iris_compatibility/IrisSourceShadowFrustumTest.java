package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.shadows.frustum.FrustumHolder;
import net.irisshaders.iris.shadows.frustum.CullEverythingFrustum;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy;
import static org.junit.jupiter.api.Assertions.*;

class IrisSourceShadowFrustumTest {
    @Test void mainViewRetainsNativeCullingAndAuxiliaryUsesResolvedFiniteDistance() {
        var nativeHolder=new FrustumHolder().setInfo(new CullEverythingFrustum(),"disabled","native");
        assertSame(nativeHolder,IrisSourceShadowFrustum.select(nativeHolder,96,1,12,16));
        try(var scope=PortalSourceRefreshPolicy.enter()) {
            var frustum=IrisSourceShadowFrustum.select(nativeHolder,96,-1,6,4).getFrustum();
            frustum.prepare(117,182,219);
            for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
                double[] center={117,182,219}; center[axis]+=sign*63;
                assertTrue(frustum.isVisible(box(center,.1)),"All directions inside64-block bound");
                center[axis]+=sign*2;
                assertFalse(frustum.isVisible(box(center,.1)),"Source effective distance caps native96-block caster range");
            }
            assertSame(nativeHolder,IrisSourceShadowFrustum.select(nativeHolder,96,0,12,16));
            assertSame(nativeHolder,IrisSourceShadowFrustum.select(nativeHolder,Float.NaN,1,12,16));
        }
        assertSame(nativeHolder,IrisSourceShadowFrustum.select(nativeHolder,96,1,12,16));
    }
    @Test void realNativeAdvancedFrustumCanOmitCastersNeededAfterCameraTurnsButAuxiliarySnapshotRetainsThem() {
        Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),1,.1f,128);
        var nativeFrustum=new AdvancedShadowCullingFrustum(projection,new Matrix4f().ortho(-96,96,-96,96,-256,256),new Vector3f(0,1,0),null);
        nativeFrustum.prepare(0,0,0);
        AABB behind=new AABB(-.1,-.1,7.9,.1,.1,8.1);
        assertFalse(nativeFrustum.isVisible(behind),"Native optimization uses the current view");
        var holder=new FrustumHolder().setInfo(nativeFrustum,"native","advanced");
        try(var scope=PortalSourceRefreshPolicy.enter()) {
            var auxiliary=IrisSourceShadowFrustum.select(holder,96,1,12,16).getFrustum();
            auxiliary.prepare(0,0,0); assertTrue(auxiliary.isVisible(behind));
            assertFalse(auxiliary.isVisible(new AABB(97,0,0,98,1,1)),"Coverage remains finite");
        }
    }
    private static AABB box(double[] center,double radius) {
        return new AABB(center[0]-radius,center[1]-radius,center[2]-radius,center[0]+radius,center[1]+radius,center[2]+radius);
    }
}
