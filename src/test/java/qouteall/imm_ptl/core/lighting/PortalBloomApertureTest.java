package qouteall.imm_ptl.core.lighting;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalBloomApertureTest {
    static List<Vector4f> rectangle(float left, float bottom, float right, float top) {
        return List.of(new Vector4f(left, bottom, 0, 1), new Vector4f(right, bottom, 0, 1),
            new Vector4f(right, top, 0, 1), new Vector4f(left, top, 0, 1));
    }
    static float distance(PortalBloomAperture.Mask mask, float x, float y) {
        float result = Float.POSITIVE_INFINITY;
        for (int i = 0; i < mask.edges().length; i += 3)
            result = Math.min(result, mask.edges()[i] * x + mask.edges()[i + 1] * y + mask.edges()[i + 2]);
        return result;
    }
    @Test void rootViewHasNoAperture() {
        assertNull(PortalBloomAperture.project(List.of(), new Matrix4f(), 100, 100));
    }
    @Test void rectangularOpeningUsesTheExactScreenBoundary() {
        var mask = PortalBloomAperture.project(List.of(rectangle(-.5f, -.5f, .5f, .5f)), new Matrix4f(), 100, 100);
        assertNotNull(mask); assertEquals(25, distance(mask, 50, 50), .001f);
        assertEquals(0, distance(mask, 25, 50), .001f);
        assertTrue(distance(mask, 24, 50) < 0);
    }
    @Test void nestedOpeningIsIntersectedInsteadOfDiscardingTheOuterFrame() {
        var mask = PortalBloomAperture.project(List.of(rectangle(-.5f, -.5f, .5f, .5f),
            rectangle(0, -.8f, .8f, .8f)), new Matrix4f(), 100, 100);
        assertNotNull(mask); assertTrue(distance(mask, 40, 50) < 0);
        assertEquals(12.5f, distance(mask, 62.5f, 50), .001f);
    }
    @Test void mirroredWindingAndViewTransformProduceTheSameInterior() {
        var rectangle = rectangle(-.5f, -.5f, .5f, .5f);
        var mask = PortalBloomAperture.project(List.of(rectangle.reversed()), new Matrix4f().scale(-1, 1, 1), 100, 100);
        assertNotNull(mask); assertEquals(25, distance(mask, 50, 50), .001f);
    }
    @Test void offscreenCornersAreClippedWithoutLosingTheVisibleAperture() {
        var mask = PortalBloomAperture.project(List.of(rectangle(-2, -.5f, .5f, 2)), new Matrix4f(), 100, 100);
        assertNotNull(mask); assertTrue(distance(mask, 5, 50) > 0);
        assertTrue(distance(mask, 80, 50) < 0);
    }
    @Test void apertureCrossingCameraPlaneRemainsFinite() {
        var opening = List.of(new Vector4f(-.5f, -.5f, -1, 1), new Vector4f(1, -.5f, .1f, 1),
            new Vector4f(1, .5f, .1f, 1), new Vector4f(-.5f, .5f, -1, 1));
        var mask = PortalBloomAperture.project(List.of(opening), new Matrix4f().perspective((float)Math.toRadians(90), 1, .05f, 100), 100, 100);
        assertNotNull(mask);
        for (float edge : mask.edges()) assertTrue(Float.isFinite(edge));
    }
    @Test void tinyOrInvisibleOpeningDoesNotPublishAnUnsafeFallback() {
        assertNull(PortalBloomAperture.project(List.of(rectangle(2, 2, 3, 3)), new Matrix4f(), 100, 100));
        assertNull(PortalBloomAperture.project(List.of(rectangle(0, 0, .01f, .01f)), new Matrix4f(), 100, 100));
    }
    @Test void insetFallbackIsInsideEveryEdgeAtPixelSafeDistance() {
        var mask = PortalBloomAperture.project(List.of(rectangle(-.9f, -.3f, .2f, .8f)), new Matrix4f().rotateZ(.4f), 100, 100);
        assertNotNull(mask);
        assertTrue(distance(mask, mask.interior().x(), mask.interior().y()) >= PortalBloomAperture.FOOTPRINT_MARGIN);
    }
}
