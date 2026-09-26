package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShipPortalMotionTest {
    private static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);
    private static final UUID A = new UUID(0, 1), AF = new UUID(0, 2), B = new UUID(0, 3), BF = new UUID(0, 4);
    private static DQuaternion tilt(double yaw, double pitch, double roll) {
        return DQuaternion.rotationByDegrees(Y, yaw).hamiltonProduct(DQuaternion.rotationByDegrees(X, pitch))
            .hamiltonProduct(DQuaternion.rotationByDegrees(Z, roll));
    }
    private static ShipPortalMotion.Pose pose(double x, double y, double z, DQuaternion q) {
        return new ShipPortalMotion.Pose(new Vec3(x, y, z), q);
    }
    private static void close(Vec3 expected, Vec3 actual) { assertTrue(expected.distanceToSqr(actual) < 1e-16, () -> expected + " != " + actual); }
    private static Vec3 through(ShipPortalMotion.Mapping m, Vec3 worldPoint) {
        return m.destination().add(m.rotation().rotate(worldPoint.subtract(m.origin().position())));
    }

    @Test void eitherFrameCanBeAttachedSecondWithoutDuplicatingItsFlippedFace() {
        for (boolean overworldFirst : new boolean[]{true, false}) {
            Set<UUID> attached = new HashSet<>();
            UUID first = overworldFirst ? A : B, second = overworldFirst ? B : A;
            UUID firstFlip = overworldFirst ? AF : BF, secondFlip = overworldFirst ? BF : AF;
            attached.add(first);
            assertEquals(first, ShipPortalMotion.sameEnd(firstFlip, first, attached::contains));
            assertNull(ShipPortalMotion.sameEnd(second, secondFlip, attached::contains));
            attached.add(second);
            assertEquals(second, ShipPortalMotion.sameEnd(secondFlip, second, attached::contains));
            assertEquals(new ShipPortalMotion.Partner(second, true),
                ShipPortalMotion.otherEnd(second, secondFlip, attached::contains));
        }
    }

    @Test void anchoringTheOtherFaceOfTheFarFrameUsesParallelRatherThanReverse() {
        Set<UUID> attached = Set.of(A, BF);
        assertEquals(new ShipPortalMotion.Partner(BF, false), ShipPortalMotion.otherEnd(B, BF, attached::contains));
        assertNull(ShipPortalMotion.otherEnd(null, null, attached::contains));
    }

    @Test void eitherDriverProducesReciprocalMappingsWhileBothFramesTranslateAndTilt() {
        for (int i = 0; i < 60; i++) {
            var a = pose(i * 3.1, 70 + i * .2, -84, tilt(i * 7, i * 3, -i * 2));
            var b = pose(-i * 1.3, 80 - i * .1, i * 2.4, tilt(-i * 11, 23 - i * 2, i));
            for (boolean reverse : new boolean[]{true, false}) {
                var ab = ShipPortalMotion.between(a, b, reverse);
                var ba = ShipPortalMotion.between(b, a, reverse);
                Vec3 point = a.position().add(new Vec3(.3, 1.4, -2.7));
                close(point, through(ba, through(ab, point)));
                close(b.position(), through(ab, a.position()));
                close(a.position(), through(ba, b.position()));
                close(b.orientation().rotate(X.scale(reverse ? -1 : 1)), ab.rotation().rotate(a.orientation().rotate(X)));
                close(b.orientation().rotate(Y), ab.rotation().rotate(a.orientation().rotate(Y)));
                close(b.orientation().rotate(Z.scale(reverse ? -1 : 1)), ab.rotation().rotate(a.orientation().rotate(Z)));
            }
        }
    }

    @Test void bothFacesProduceTheSameConnectionWhenFarAnchorFaceChanges() {
        var a = pose(4, 70, -80, tilt(25, -11, 3));
        var b = pose(-1, 88, 2, tilt(-47, 32, 9));
        var bf = new ShipPortalMotion.Pose(b.position(), b.orientation().hamiltonProduct(ShipPortalMotion.FLIP));
        var reverse = ShipPortalMotion.between(a, b, true);
        var parallel = ShipPortalMotion.between(a, bf, false);
        for (Vec3 axis : new Vec3[]{X, Y, Z}) close(reverse.rotation().rotate(axis), parallel.rotation().rotate(axis));
    }

    @Test void movingOnlyDestinationTranslationCannotBeSkipped() {
        var a = pose(5, 72, -90, tilt(0, 0, 0));
        var b = pose(0, 88, -3, tilt(180, 0, 0));
        var old = ShipPortalMotion.between(a, b, true);
        var moved = ShipPortalMotion.between(a, new ShipPortalMotion.Pose(b.position().add(0, 0, 12), b.orientation()), true);
        assertTrue(ShipPortalMotion.changed(moved, a, old.destination(), old.rotation()));
        assertFalse(ShipPortalMotion.changed(old, a, old.destination(), old.rotation()));
    }

    @Test void movingOnlyDestinationTiltCannotBeSkipped() {
        var a = pose(5, 72, -90, tilt(0, 0, 0));
        var b = pose(0, 88, -3, tilt(180, 0, 0));
        var old = ShipPortalMotion.between(a, b, true);
        var moved = ShipPortalMotion.between(a, new ShipPortalMotion.Pose(b.position(), tilt(180, 30.1, 0)), true);
        assertTrue(ShipPortalMotion.changed(moved, a, old.destination(), old.rotation()));
    }

    @Test void detachFarEndKeepsItsLastPoseWhileRemainingFrameContinuesMoving() {
        var a = pose(-10, 70, -90, tilt(20, 12, 9));
        var b = pose(50, 90, 120, tilt(-13, 30.1, 17));
        var before = ShipPortalMotion.between(a, b, true);
        var after = ShipPortalMotion.toFixed(pose(80, 75, 33, tilt(42, -22, 7)), b.position(), before.destinationBasis());
        close(b.position(), after.destination());
        for (Vec3 axis : new Vec3[]{X, Y, Z}) {
            close(before.destinationBasis().rotate(axis), after.destinationBasis().rotate(axis));
        }
    }

    @Test void detachNearEndKeepsSurvivingReturnFramesCurrentLock() {
        var a = pose(-10, 70, -90, tilt(20, 12, 9));
        var b = pose(50, 90, 120, tilt(-13, 30.1, 17));
        var ab = ShipPortalMotion.between(a, b, true);
        var returnLock = ab.rotation().getConjugated().hamiltonProduct(b.orientation());
        var ba = ShipPortalMotion.toFixed(pose(60, 92, 125, tilt(4, 18, 2)), a.position(), returnLock);
        close(a.position(), ba.destination());
        close(a.orientation().rotate(X.scale(-1)), ba.destinationBasis().rotate(X));
        close(a.orientation().rotate(Y), ba.destinationBasis().rotate(Y));
    }

    @Test void singleEndUsesTheExistingFixedDestinationConvention() {
        DQuaternion initial = tilt(12, 14, 0), initialTransform = tilt(-80, 3, 6);
        DQuaternion lock = initialTransform.hamiltonProduct(initial);
        var stationary = ShipPortalMotion.toFixed(pose(3, 4, 5, initial), new Vec3(-8, 70, -3), lock);
        close(initialTransform.rotate(Z), stationary.rotation().rotate(Z));
        var moved = ShipPortalMotion.toFixed(pose(6, 7, 8, tilt(33, -27, 19)), stationary.destination(), lock);
        close(stationary.destination(), moved.destination());
        close(lock.rotate(Y), moved.destinationBasis().rotate(Y));
    }

    @Test void quaternionSignChangeIsNotPhysicalMotion() {
        DQuaternion q = tilt(120, 40, -10);
        assertTrue(ShipPortalMotion.sameRotation(q, new DQuaternion(-q.x, -q.y, -q.z, -q.w)));
    }

    @Test void unresolvedAttachedEndHoldsUntilBothCarrierPosesAreAvailable() {
        var a = pose(3, 70, 9, tilt(15, 12, 3));
        var b = pose(-2, 90, 8, tilt(-21, 30, 4));
        var other = new ShipPortalMotion.Partner(B, true);
        assertNull(ShipPortalMotion.resolve(A, other, Map.of(A, a), Vec3.ZERO, DQuaternion.identity));
        assertNull(ShipPortalMotion.resolve(A, other, Map.of(B, b), Vec3.ZERO, DQuaternion.identity));
        var resolved = ShipPortalMotion.resolve(A, other, Map.of(A, a, B, b), Vec3.ZERO, DQuaternion.identity);
        assertNotNull(resolved);
        close(b.position(), resolved.destination());
    }

    @Test void allFourFacesShareCoherentOriginDestinationAndOppositeNormals() {
        var a = pose(3, 70, 9, tilt(15, 12, 3));
        var b = pose(-2, 90, 8, tilt(-21, 30, 4));
        var m = ShipPortalMotion.between(a, b, true);
        var f = m.flipped(); var r = m.returning(true); var p = m.returning(false);
        close(a.position(), f.origin().position()); close(b.position(), f.destination());
        close(b.position(), r.origin().position()); close(a.position(), r.destination());
        close(b.position(), p.origin().position()); close(a.position(), p.destination());
        close(a.orientation().rotate(Z).scale(-1), f.origin().orientation().rotate(Z));
        close(b.orientation().rotate(Z), r.origin().orientation().rotate(Z));
        close(b.orientation().rotate(Z).scale(-1), p.origin().orientation().rotate(Z));
        assertTrue(ShipPortalMotion.changed(r, pose(-2, 90, 8, DQuaternion.identity), a.position(), r.rotation()));
    }
}
