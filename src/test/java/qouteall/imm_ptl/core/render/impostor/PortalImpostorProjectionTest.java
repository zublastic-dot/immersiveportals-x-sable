package qouteall.imm_ptl.core.render.impostor;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalImpostorProjectionTest {
    private static final Vec3 W = new Vec3(1, 0, 0), H = new Vec3(0, 1, 0);

    @Test void localUvMapsToExactRectangularCorners() {
        var p = PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), Vec3.ZERO, W, H, 1, .5).orElseThrow();
        assertTrue(p.fullyVisible());
        assertEquals(new Vector4f(-.5f, -.25f, 0, 1), p.clipFromUv().transform(new Vector4f(0, 0, 0, 1)));
        assertEquals(new Vector4f(.5f, .25f, 0, 1), p.clipFromUv().transform(new Vector4f(1, 1, 0, 1)));
    }

    @Test void perspectiveRectificationMatchesPhysicalPointWithoutTriangleSeam() {
        Matrix4f perspective = new Matrix4f().perspective((float)Math.toRadians(70), 1.5f, .1f, 200_000);
        Vec3 w = new Vec3(.8, 0, .6), origin = new Vec3(2, 1, -20);
        var p = PortalImpostorProjection.create(new Matrix4f(), perspective, origin, w, H, 8, 5).orElseThrow();
        for (float u : new float[]{0, .25f, .51f, 1}) for (float v : new float[]{0, .31f, .9f, 1}) {
            Vec3 world = origin.add(w.scale((u - .5) * 8)).add(H.scale((v - .5) * 5));
            Vector4f expected = perspective.transform(new Vector4f((float)world.x, (float)world.y, (float)world.z, 1));
            Vector4f actual = p.clipFromUv().transform(new Vector4f(u, v, 0, 1));
            assertTrue(expected.equals(actual, .00001f));
        }
    }

    @Test void movingShipOrientationAndCameraRelativeOriginChangeProjection() {
        var first = PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), Vec3.ZERO, W, H, 1, 1).orElseThrow();
        var moved = PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), new Vec3(.1, .2, 0), H, W.scale(-1), 1, 1).orElseThrow();
        assertEquals(new Vector4f(.6f, -.3f, 0, 1), moved.clipFromUv().transform(new Vector4f(0, 0, 0, 1)));
        assertNotEquals(first.clipFromUv(), moved.clipFromUv());
    }

    @Test void rejectsNonFiniteOrCollapsedGeometry() {
        assertTrue(PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), Vec3.ZERO, W, W, 1, 1).isEmpty());
        assertTrue(PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), Vec3.ZERO, W, H, Double.NaN, 1).isEmpty());
        assertTrue(PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), new Vec3(Double.NaN, 0, 0), W, H, 1, 1).isEmpty());
        assertTrue(PortalImpostorProjection.create(new Matrix4f().m00(Float.NaN), new Matrix4f(), Vec3.ZERO, W, H, 1, 1).isEmpty());
    }

    @Test void partialAndNearClippedAperturesCanDrawButCannotBeCaptured() {
        assertFalse(PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), new Vec3(.9, 0, 0), W, H, 1, 1).orElseThrow().fullyVisible());
        Matrix4f perspective = new Matrix4f().perspective(1, 1, 1, 1000);
        assertFalse(PortalImpostorProjection.create(new Matrix4f(), perspective, new Vec3(0, 0, -.5), W, H, .1, .1).orElseThrow().fullyVisible());
        assertFalse(PortalImpostorProjection.create(new Matrix4f(), perspective, new Vec3(0, 0, 10), W, H, 1, 1).orElseThrow().fullyVisible());
    }

    @Test void preservesFarDepthInsteadOfDrawingThroughTerrain() {
        Matrix4f perspective = new Matrix4f().perspective(1, 1, .1f, 100);
        var beyondFar = PortalImpostorProjection.create(new Matrix4f(), perspective, new Vec3(0, 0, -1000), W, H, 10, 10).orElseThrow();
        var center = beyondFar.clipFromUv().transform(new Vector4f(.5f, .5f, 0, 1));
        assertTrue(center.z > center.w);
        assertFalse(beyondFar.fullyVisible());
    }

    @Test void returnedMatrixCannotMutatePublishedProjection() {
        var p = PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), Vec3.ZERO, W, H, 1, 1).orElseThrow();
        Matrix4f expected = p.clipFromUv(); p.clipFromUv().zero();
        assertEquals(expected, p.clipFromUv());
    }
}
