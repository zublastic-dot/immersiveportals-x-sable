package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalSunGeometry.*;

class PortalSunGeometryTest {
    private static final Vec3 NORMAL = new Vec3(1, 0, 0), U = new Vec3(0, 1, 0), V = new Vec3(0, 0, 1);
    private static final World OPEN = p -> Cell.OPEN;

    private Hit aperture(Vec3 point, Vec3 direction) {
        return hit(point, direction, NORMAL, Vec3.ZERO, U, V, 2, 3);
    }

    @Test void normalizesRayDistanceAndReportsApertureCoordinates() {
        Hit h = aperture(new Vec3(4, .5, -.75), new Vec3(-8, 0, 0));
        assertNotNull(h);
        assertEquals(4, h.distance(), 1e-12);
        assertEquals(.5, h.u(), 1e-12);
        assertEquals(-.75, h.v(), 1e-12);
        assertEquals(new Vec3(0, .5, -.75), h.position());
    }

    @Test void apertureHasHardEdgesAndRejectsFrameGrazing() {
        assertNotNull(aperture(new Vec3(4, 1.99999, 2.99999), new Vec3(-1, 0, 0)));
        assertNull(aperture(new Vec3(4, 2, 0), new Vec3(-1, 0, 0)));
        assertNull(aperture(new Vec3(4, 0, -3), new Vec3(-1, 0, 0)));
        assertNull(aperture(new Vec3(4, 2.00001, 0), new Vec3(-1, 0, 0)));
        assertNull(aperture(new Vec3(4, 0, 3.00001), new Vec3(-1, 0, 0)));
    }

    @Test void rejectsParallelBackFacingNegativeAndMalformedRays() {
        for (Vec3 direction : new Vec3[]{new Vec3(0, 1, 0), new Vec3(1, 0, 0), Vec3.ZERO,
            new Vec3(Double.NaN, 1, 0), new Vec3(Double.POSITIVE_INFINITY, 0, 0)})
            assertNull(aperture(new Vec3(4, 0, 0), direction));
        assertNull(aperture(new Vec3(-4, 0, 0), new Vec3(1, 0, 0)));
        assertNull(aperture(Vec3.ZERO, new Vec3(-1, 0, 0)));
        assertNull(hit(NORMAL, NORMAL.scale(-1), NORMAL, Vec3.ZERO, U, U, 2, 3));
        assertNull(hit(NORMAL, NORMAL.scale(-1), NORMAL.scale(2), Vec3.ZERO, U, V, 2, 3));
        assertNull(hit(NORMAL, NORMAL.scale(-1), NORMAL, Vec3.ZERO, U, V, Double.NaN, 3));
    }

    @Test void fullRotationPreservesHitsWithoutSnappingTheSourcePlane() {
        var rotation = DQuaternion.rotationByDegrees(new Vec3(1, 2, 3), 31);
        Vec3 center = new Vec3(117.19187393, 182.12493317, 218.71214239);
        Vec3 point = rotation.rotate(new Vec3(4, .5, -.75)).add(center);
        Hit h = hit(point, rotateDirection(new Vec3(-1, 0, 0), rotation),
            rotation.rotate(NORMAL), center, rotation.rotate(U), rotation.rotate(V), 2, 3);
        assertNotNull(h);
        assertEquals(4, h.distance(), 1e-10);
        assertEquals(.5, h.u(), 1e-10);
        assertEquals(-.75, h.v(), 1e-10);
        assertTrue(h.position().distanceTo(rotation.rotate(new Vec3(0, .5, -.75)).add(center)) < 1e-10);
    }

    @Test void directionRotationIgnoresScaleAndRejectsInvalidQuaternions() {
        var rotation = DQuaternion.rotationByDegrees(U, 90);
        Vec3 actual = rotateDirection(new Vec3(200, 0, 0), rotation.multiply(3));
        assertNotNull(actual);
        assertEquals(1, actual.length(), 1e-12);
        assertTrue(actual.distanceTo(new Vec3(0, 0, -1)) < 1e-12);
        assertEquals(U, rotateDirection(U.scale(50), null));
        assertNull(rotateDirection(U, new DQuaternion(0, 0, 0, 0)));
        assertNull(rotateDirection(U, new DQuaternion(Double.NaN, 0, 0, 1)));
        assertNull(rotateDirection(Vec3.ZERO, rotation));
    }

    @Test void destinationRoofAndSourceOccluderIndependentlyStopSunlight() {
        Vec3 receiver = new Vec3(4.5, .5, .5);
        Hit h = hit(receiver, new Vec3(-1, 1, 0), NORMAL, new Vec3(0, 3, .5), U, V, 3, 3);
        assertNotNull(h);
        assertTrue(clearSegment(OPEN, receiver, h.position(), 64));
        assertFalse(clearSegment(p -> p.y() == 2 ? Cell.CLOSED : Cell.OPEN, receiver, h.position(), 64));
        Vec3 sourceExit = new Vec3(10.5, 4.5, 12.5);
        assertTrue(clearToSky(OPEN, sourceExit, new Vec3(1, 2, 0), 12, 64));
        assertFalse(clearToSky(p -> p.y() == 7 ? Cell.CLOSED : Cell.OPEN,
            sourceExit, new Vec3(1, 2, 0), 12, 64));
        assertTrue(clearSegment(OPEN, receiver, h.position(), 64), "source occlusion does not alter receiver geometry");
    }

    @Test void segmentDoesNotSampleBeyondApertureEndpoint() {
        assertTrue(clearSegment(p -> p.x() >= 2 ? Cell.CLOSED : Cell.OPEN,
            new Vec3(.5, .5, .5), new Vec3(2, .5, .5), 2));
        assertTrue(clearSegment(p -> p.x() >= 2 || p.x() < 0 ? Cell.CLOSED : Cell.OPEN,
            new Vec3(2, .5, .5), new Vec3(0, .5, .5), 2));
    }

    @Test void skyTraceStopsAtBuildHeightAndRequiresAnUpwardSun() {
        var reads = new AtomicInteger();
        World world = p -> { reads.incrementAndGet(); assertTrue(p.y() < 12); return Cell.OPEN; };
        assertTrue(clearToSky(world, new Vec3(.5, 9.5, .5), U, 12, 3));
        assertEquals(3, reads.get());
        reads.set(0);
        assertFalse(clearToSky(world, new Vec3(.5, 9.5, .5), NORMAL, 12, 32));
        assertFalse(clearToSky(world, new Vec3(.5, 9.5, .5), U.scale(-1), 12, 32));
        assertTrue(clearToSky(world, new Vec3(.5, 12, .5), U, 12, 32));
        assertEquals(0, reads.get());
    }

    @Test void diagonalSkyEndpointDoesNotReadAboveTheBuildCeiling() {
        World world = p -> { assertTrue(p.y() < 320, "ceiling is exclusive"); return Cell.OPEN; };
        for (Vec3 direction : new Vec3[]{new Vec3(.3, .7, .2), new Vec3(-.3, .7, -.2), new Vec3(1, 1, 1)})
            assertTrue(clearToSky(world, new Vec3(117.19187393, 182.12493317, 218.71214239),
                direction, 320, 2048));
    }

    @Test void unknownOrMissingCellsNeverBecomeSunlight() {
        Vec3 start = new Vec3(.5, .5, .5), end = new Vec3(3.5, .5, .5);
        assertFalse(clearSegment(p -> p.x() == 1 ? Cell.UNKNOWN : Cell.OPEN, start, end, 32));
        assertFalse(clearSegment(p -> p.x() == 1 ? null : Cell.OPEN, start, end, 32));
        assertFalse(clearSegment(p -> Cell.UNKNOWN, start, end, 32));
        assertFalse(clearToSky(p -> p.y() == 3 ? Cell.UNKNOWN : Cell.OPEN, start, U, 12, 32));
    }

    @Test void tiedEdgeAndCornerCrossingsCannotSkipTouchingBlockers() {
        Vec3 start = new Vec3(.5, .5, .5), end = new Vec3(1.5, 1.5, 1.5);
        assertTrue(clearSegment(OPEN, start, end, 8));
        for (int mask = 1; mask <= 7; mask++) {
            Pos blocker = new Pos(mask & 1, (mask >> 1) & 1, (mask >> 2) & 1);
            assertFalse(clearSegment(p -> p.equals(blocker) ? Cell.CLOSED : Cell.OPEN, start, end, 32),
                "corner contact must not skip " + blocker);
        }
    }

    @Test void raysOnVoxelPlanesConservativelyCheckBothSides() {
        Vec3 start = new Vec3(.5, 1, 1), end = new Vec3(1.5, 1, 1);
        assertTrue(clearSegment(OPEN, start, end, 8));
        assertFalse(clearSegment(p -> p.y() == 0 && p.z() == 0 ? Cell.CLOSED : Cell.OPEN, start, end, 8));
    }

    @Test void fixedCellReadBudgetFailsClosedWithoutExtraWorldQueries() {
        var reads = new AtomicInteger();
        World world = p -> { reads.incrementAndGet(); return Cell.OPEN; };
        assertFalse(clearSegment(world, new Vec3(.5, .5, .5), new Vec3(100, .5, .5), 7));
        assertEquals(7, reads.get());
        reads.set(0);
        assertFalse(clearToSky(world, new Vec3(.5, .5, .5), new Vec3(1, .000001, 0), 320, 7));
        assertEquals(7, reads.get());
        reads.set(0);
        assertFalse(clearSegment(world, Vec3.ZERO, NORMAL, MAX_CELL_READS + 1));
        assertFalse(clearSegment(world, Vec3.ZERO, NORMAL, 0));
        assertFalse(clearSegment(world, Vec3.ZERO, Vec3.ZERO, 10));
        assertEquals(0, reads.get());
    }

    @Test void negativeAndLargeCoordinatesPreserveVisibilityAndHardBlockerEdges() {
        Vec3 offset = new Vec3(-20_000_000, -60, 20_000_000);
        Vec3 start = offset.add(.5, .5, .5), end = offset.add(3.5, .5, .5);
        assertTrue(clearSegment(OPEN, start, end, 4));
        Pos block = new Pos(-19_999_998, -60, 20_000_000);
        assertFalse(clearSegment(p -> p.equals(block) ? Cell.CLOSED : Cell.OPEN, start, end, 4));
    }
}
