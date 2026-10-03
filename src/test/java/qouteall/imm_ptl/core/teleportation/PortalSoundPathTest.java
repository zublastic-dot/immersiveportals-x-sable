package qouteall.imm_ptl.core.teleportation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSoundPathTest {
    private static final Vec3 U = new Vec3(1, 0, 0), V = new Vec3(0, 1, 0);
    private static PortalSoundPath.Frame frame() {
        return new PortalSoundPath.Frame(Vec3.ZERO, new Vec3(100, 10, 0), U, V, U, V, 3, 3);
    }

    @Test void apertureBearingAndCompleteDistanceAreAppliedOnce() {
        var f = frame();
        Vec3 source = new Vec3(0, 0, 5), listener = new Vec3(100, 10, -25);
        var path = PortalSoundPath.solve(f, source, listener);
        assertNotNull(path);
        assertEquals(30, path.distance(), 1e-9);
        assertEquals(path.distance(), path.presentation().distanceTo(listener), 1e-9);
        assertEquals(new Vec3(100, 10, 5), path.presentation());
        // Old code clamps listener-side gain to zero at sixteen blocks. Native
        // attenuation with a larger sound radius must still hear this thirty-block path.
        assertEquals(.53125, 1 - path.presentation().distanceTo(listener) / 64, 1e-9);
    }

    @Test void unfoldedStraightRayChoosesCorrectInteriorAperturePoint() {
        var f = frame();
        var p = PortalSoundPath.solve(f, new Vec3(-2, 1, 4), new Vec3(102, 11, -4));
        assertNotNull(p);
        assertEquals(0, p.entry().x, 1e-9);
        assertEquals(1, p.entry().y, 1e-9);
        assertEquals(Math.sqrt(80), p.distance(), 1e-9);
    }

    @Test void outsideRayUsesFiniteFrameEdgeAndCannotCutThroughSolidRim() {
        var f = frame();
        Vec3 s = new Vec3(10, 0, 2), l = new Vec3(110, 10, -2);
        var p = PortalSoundPath.solve(f, s, l);
        assertNotNull(p);
        assertEquals(3, p.entry().x, 1e-8);
        assertEquals(0, p.entry().y, 1e-4);
        assertEquals(2 * Math.sqrt(53), p.distance(), 1e-7);
        assertTrue(p.distance() > f.transform(s).distanceTo(l));
    }

    @Test void rotationAndLargeTranslationPreserveDistanceAndBearing() {
        var f = new PortalSoundPath.Frame(new Vec3(20_000_000, 40, -20_000_000), new Vec3(14, 50, 28),
            U, V, new Vec3(0, 0, -1), V, 3, 3);
        Vec3 s = f.origin().add(1, 1, 5);
        Vec3 l = f.destination().add(-6, 1, -1);
        var p = PortalSoundPath.solve(f, s, l);
        assertNotNull(p);
        assertEquals(11, p.distance(), 1e-8);
        assertEquals(11, p.presentation().distanceTo(l), 1e-8);
        assertTrue(p.presentation().subtract(l).normalize().distanceTo(p.exit().subtract(l).normalize()) < 1e-8);
        assertTrue(f.inverse(f.transform(s)).distanceTo(s) < 1e-7);
        Vec3 vector = new Vec3(.4, -.7, .1);
        assertTrue(f.inverseVector(f.transformVector(vector)).distanceTo(vector) < 1e-10);
    }

    @Test void presentationRemainsContinuousAtThresholdAndMicroDistances() {
        var f = frame();
        Vec3 s = new Vec3(0, 0, 5);
        for (double offset : new double[]{.001, .0001, .00001, .000001, .00000001, 0}) {
            Vec3 before = new Vec3(100, 10, -offset);
            var routed = PortalSoundPath.solve(f, s, before);
            assertNotNull(routed);
            Vec3 crossedListener = f.inverse(before);
            assertEquals(s.distanceTo(crossedListener), routed.distance(), 1e-8);
            assertEquals(routed.distance(), routed.presentation().distanceTo(before), 1e-8);
            assertTrue(s.distanceTo(f.inverse(routed.presentation())) < 1e-8);
        }
    }

    @Test void backFacesAndMalformedGeometryDoNotCreateFalseRoutes() {
        var f = frame();
        assertNull(PortalSoundPath.solve(f, new Vec3(0, 0, -1), new Vec3(100, 10, -1)));
        assertNull(PortalSoundPath.solve(f, new Vec3(0, 0, 1), new Vec3(100, 10, 1)));
        assertNull(PortalSoundPath.solve(f, new Vec3(Double.NaN, 0, 1), new Vec3(100, 10, -1)));
        var scaled = new PortalSoundPath.Frame(f.origin(), f.destination(), U, V, U.scale(2), V, 3, 3);
        assertNull(PortalSoundPath.solve(scaled, new Vec3(0, 0, 1), new Vec3(100, 10, -1)));
    }

    @Test void cornerAndPortalPlaneDegeneraciesRemainFinite() {
        var f = frame();
        for (double x : new double[]{-10, -3, 0, 3, 10}) {
            for (double y : new double[]{-10, -3, 0, 3, 10}) {
                var p = PortalSoundPath.solve(f, new Vec3(x, y, 0), f.destination());
                assertNotNull(p);
                assertTrue(PortalSoundPath.finite(p.presentation()));
                assertEquals(p.distance(), p.presentation().distanceTo(f.destination()), 1e-8);
                assertTrue(Math.abs(p.entry().x) <= 3 && Math.abs(p.entry().y) <= 3);
            }
        }
    }
}
