package qouteall.imm_ptl.core.compat.dh_compatibility;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.q_misc_util.my_util.DQuaternion;

import static org.junit.jupiter.api.Assertions.*;

class DhNearbyLevelRetentionTest {
    private final Object session = new Object(), main = new Object();
    private final DhNearbyLevelRetention.Policy<Object> policy = new DhNearbyLevelRetention.Policy<>();

    @Test void closestFourDestinationsDoNotConsumeActualWorldSlot() {
        Object[] worlds = {new Object(), new Object(), new Object(), new Object(), new Object(), new Object()};
        var selection = policy.select(session, main, 100);
        for (int i = 0; i < worlds.length; i++) selection.offer(worlds[i], 60 - i * 10);
        selection.offer(main, 0);
        policy.publish(selection);
        assertTrue(policy.retains(main, 100));
        for (int i = 0; i < worlds.length; i++) assertEquals(i >= 2, policy.retains(worlds[i], 100));
    }

    @Test void duplicatesUseNearestOpeningAndIdentityNotEquals() {
        Object a = new String("same key"), replacement = new String("same key");
        var selection = policy.select(session, main, 10);
        selection.offer(a, 63);
        selection.offer(a, 3);
        for (int i = 0; i < 3; i++) selection.offer(new Object(), 20 + i);
        var snapshot = selection.finish();
        assertEquals(4, snapshot.remote.size());
        assertTrue(snapshot.retains(a, 10));
        assertFalse(snapshot.retains(replacement, 10));
    }

    @Test void admissionAndHysteresisHaveExactBoundaries() {
        Object world = new Object(), notYetRetained = new Object();
        var first = policy.select(session, main, 0);
        first.offer(world, 64);
        first.offer(notYetRetained, Math.nextUp(64.0));
        policy.publish(first);
        assertTrue(policy.retains(world, 0));
        assertFalse(policy.retains(notYetRetained, 0));
        var next = policy.select(session, main, 1);
        next.offer(world, 80);
        next.offer(notYetRetained, 65);
        policy.publish(next);
        assertTrue(policy.retains(world, 1));
        assertFalse(policy.retains(notYetRetained, 1));
        var outside = policy.select(session, main, 2);
        outside.offer(world, Math.nextUp(80.0));
        policy.publish(outside);
        assertFalse(policy.retains(world, 2));
    }

    @Test void absentOrInvalidCandidatesImmediatelyResumeNativeCleanup() {
        Object world = new Object();
        var first = policy.select(session, main, 0);
        first.offer(world, 1);
        policy.publish(first);
        var next = policy.select(session, main, 1);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 81}) next.offer(world, invalid);
        next.offer(null, 1);
        policy.publish(next);
        assertFalse(policy.retains(world, 1));
        assertFalse(policy.retains(null, 1));
        assertTrue(policy.retains(main, 1));
    }

    @Test void lookDirectionIsNotAnInputAndRepeatedNearbyTicksSurviveThirtySeconds() {
        Object world = new Object();
        for (long second = 0; second <= 45; second++) {
            long now = second * 1_000_000_000L;
            var selection = policy.select(session, main, now);
            selection.offer(world, 2);
            policy.publish(selection);
            assertTrue(policy.retains(world, now + 999_999_999L));
        }
    }

    @Test void stalePublisherAndBackwardClockFailClosedIncludingAcrossLongRollover() {
        Object world = new Object();
        long start = Long.MAX_VALUE - 10;
        var selection = policy.select(session, main, start);
        selection.offer(world, 2);
        policy.publish(selection);
        assertTrue(policy.retains(world, start + DhNearbyLevelRetention.FRESH_NANOS - 1));
        assertFalse(policy.retains(world, start + DhNearbyLevelRetention.FRESH_NANOS));
        assertFalse(policy.retains(world, start - 1));
    }

    @Test void newSessionAndStaleSnapshotDoNotInheritExitRadius() {
        Object world = new Object(), newSession = new Object();
        var selection = policy.select(session, main, 0);
        selection.offer(world, 2);
        policy.publish(selection);
        var changed = policy.select(newSession, main, 1);
        changed.offer(world, 70);
        assertFalse(changed.finish().retains(world, 1));
        var stale = policy.select(session, main, DhNearbyLevelRetention.FRESH_NANOS);
        stale.offer(world, 70);
        assertFalse(stale.finish().retains(world, DhNearbyLevelRetention.FRESH_NANOS));
    }

    @Test void crossingRefreshesImmediatelyAndOldCurrentWorldCanBecomeNearbyDestination() {
        Object destination = new Object();
        var selection = policy.select(session, main, 0);
        selection.offer(destination, 2);
        policy.publish(selection);
        assertFalse(policy.refreshDue(session, main, 999_999_999L));
        assertTrue(policy.refreshDue(session, main, 1_000_000_000L));
        assertTrue(policy.refreshDue(session, destination, 1));
        var crossed = policy.select(session, destination, 1);
        crossed.offer(main, 2);
        policy.publish(crossed);
        assertTrue(policy.retains(main, 1));
        assertTrue(policy.retains(destination, 1));
    }

    @Test void dimensionRemovalDoesNotMutatePublishedReadersOrRenewLease() {
        Object a = new Object(), b = new Object();
        var selection = policy.select(session, main, 1);
        selection.offer(a, 1); selection.offer(b, 2);
        var before = selection.finish();
        var after = before.without(world -> world == a);
        assertTrue(before.retains(a, 2));
        assertFalse(after.retains(a, 2));
        assertTrue(after.retains(b, 2));
        assertEquals(before.seenNanos, after.seenNanos);
        policy.publish(selection);
        policy.remove(world -> world == a);
        assertFalse(policy.retains(a, 2));
        assertTrue(policy.retains(b, 2));
        policy.clear();
        assertFalse(policy.retains(main, 2));
        assertFalse(policy.retains(b, 2));
        assertTrue(policy.refreshDue(session, main, 2));
    }

    @Test void clearedWeakReferencesNeverMatchOrKeepSessionAlive() {
        Object world = new Object();
        var selection = policy.select(session, main, 0);
        selection.offer(world, 1);
        var snapshot = selection.finish();
        snapshot.remote.getFirst().clear();
        assertFalse(snapshot.retains(world, 1));
        assertTrue(snapshot.retains(main, 1));
        snapshot.session.clear();
        assertFalse(snapshot.retains(main, 1));
        assertFalse(snapshot.retains(null, 1));
    }

    @Test void largeRotatedTranslatedOpeningUsesEdgeDistanceNotCentre() {
        var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("test:arbitrary"));
        var pose = new UnilateralPortalState(dimension, new Vec3(1200, 220, -830),
            DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90), 400, 100);
        Vec3 player = pose.transformLocalToGlobal(190, 0, 3);
        assertTrue(player.distanceTo(pose.position()) > 64);
        double distance = RectangularPortalShape.INSTANCE.roughDistanceToPortalShape(pose, player);
        assertEquals(3, distance, 0.0001);
        Object world = new Object();
        var selection = policy.select(session, main, 0);
        selection.offer(world, distance);
        assertTrue(selection.finish().retains(world, 0));
    }
}
