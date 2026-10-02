package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSourceRefreshPolicyTest {
    @Test void freshnessAndRetryDeadlineAreIndependentOfPortalVisibility() {
        long now = 4_000_000_000L;
        assertTrue(PortalSourceRefreshPolicy.due(now, -1, Long.MIN_VALUE));
        assertFalse(PortalSourceRefreshPolicy.due(now, now - 200_000_000, now));
        assertTrue(PortalSourceRefreshPolicy.due(now, now - 250_000_000, now));
        assertFalse(PortalSourceRefreshPolicy.due(now, -1, now + PortalSourceRefreshPolicy.RETRY_NANOS));
        assertTrue(PortalSourceRefreshPolicy.due(now + PortalSourceRefreshPolicy.RETRY_NANOS, -1, now + PortalSourceRefreshPolicy.RETRY_NANOS));
    }
    @Test void offscreenMemoryBoundRejectsOverflowAndOversizedMainTargets() {
        assertTrue(PortalSourceRefreshPolicy.supportedSize(1920,1080));
        assertTrue(PortalSourceRefreshPolicy.supportedSize(3840,2160));
        assertFalse(PortalSourceRefreshPolicy.supportedSize(7680,4320));
        assertFalse(PortalSourceRefreshPolicy.supportedSize(Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertFalse(PortalSourceRefreshPolicy.supportedSize(0,1080));
    }
    @Test void scopeBlocksRecursionAndRestoresOnException() {
        assertFalse(PortalSourceRefreshPolicy.isRendering());
        assertThrows(IllegalArgumentException.class, () -> {
            try (var scope = PortalSourceRefreshPolicy.enter()) {
                assertTrue(PortalSourceRefreshPolicy.isRendering());
                assertThrows(IllegalStateException.class, PortalSourceRefreshPolicy::enter);
                throw new IllegalArgumentException("native render failed");
            }
        });
        assertFalse(PortalSourceRefreshPolicy.isRendering());
    }
    @Test void transformedVirtualCameraPreservesReceiverCoordinatesForRotatedPortals() {
        Vec3 sourceCenter = new Vec3(117.192,182.125,218.712), targetCenter = new Vec3(14.5,50,28);
        Vec3 offset = new Vec3(3.125,-1.75,8.5);
        for (float angle : new float[]{0,.4f,1.5707963f,3.1415926f}) {
            Matrix4f transform = new Matrix4f().rotateXYZ(angle, angle * .4f, angle * -.7f);
            Vector3f vx=transform.transformDirection(new Vector3f(1,0,0));
            Vector3f vy=transform.transformDirection(new Vector3f(0,1,0));
            Vector3f vz=transform.transformDirection(new Vector3f(0,0,1));
            Vec3 x=new Vec3(vx.x,vx.y,vx.z), y=new Vec3(vy.x,vy.y,vy.z), z=new Vec3(vz.x,vz.y,vz.z);
            Vec3 camera=PortalSourceRefreshPolicy.sourcePoint(targetCenter.add(offset),targetCenter,sourceCenter,x,y,z);
            Vector3f recovered=PortalSourceRefreshPolicy.cameraTransform(x,y,z).transformDirection(
                new Vector3f((float)(camera.x-sourceCenter.x),(float)(camera.y-sourceCenter.y),(float)(camera.z-sourceCenter.z)));
            assertEquals(offset.x,recovered.x,2e-5); assertEquals(offset.y,recovered.y,2e-5); assertEquals(offset.z,recovered.z,2e-5);
        }
    }
    @Test void onlyNearbyReceiverVolumeNeedsInvisibleSourceRefresh() {
        var min=new PortalLightField.Pos(15,45,20); var max=new PortalLightField.Pos(22,58,35);
        assertEquals(0,PortalSourceRefreshPolicy.distanceSquared(new Vec3(17,50,30),min,max));
        assertEquals(256,PortalSourceRefreshPolicy.distanceSquared(new Vec3(-1,50,30),min,max));
        assertTrue(PortalSourceRefreshPolicy.distanceSquared(new Vec3(-2,50,30),min,max)>256);
    }
}
