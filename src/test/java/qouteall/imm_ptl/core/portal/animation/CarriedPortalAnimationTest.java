package qouteall.imm_ptl.core.portal.animation;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalState;
import qouteall.q_misc_util.my_util.DQuaternion;

import static org.junit.jupiter.api.Assertions.*;

class CarriedPortalAnimationTest {
    private static PortalState state(double offset) {
        return new PortalState(Level.NETHER, new Vec3(offset, 80, 3),
            Level.OVERWORLD, new Vec3(20 + offset, 110, 40),
            1, DQuaternion.identity, DQuaternion.identity, 2, 3);
    }

    private static ClientPortalAnimationManagement.RunningDefaultAnimation animation() {
        return new ClientPortalAnimationManagement.RunningDefaultAnimation(
            state(0), state(2), 100, 200, TimingFunction.linear, false);
    }

    @Test void expiredReturnFaceAnimationCannotRestoreTheOldDestinationDimension() {
        // The .14 crash: a carrier has entered the Nether, so its return face now
        // points Nether -> Nether. The old Overworld animation expired between frames.
        assertNull(animation().getCurrentState(201, Level.NETHER, Level.NETHER));
    }

    @Test void expiredAnimationCannotRestoreTheOldOriginDimension() {
        assertNull(animation().getCurrentState(201, Level.OVERWORLD, Level.OVERWORLD));
    }

    @Test void dimensionChangesCancelAtAndBeforeTheFinalFrameToo() {
        for (long time : new long[]{150, 200}) {
            assertNull(animation().getCurrentState(time, Level.NETHER, Level.NETHER));
            assertNull(animation().getCurrentState(time, Level.OVERWORLD, Level.OVERWORLD));
        }
    }

    @Test void mismatchedAnimationEndpointsAreRejectedBeforeInterpolation() {
        var animation = animation();
        var end = animation.toState;
        animation.toState = new PortalState(Level.NETHER, end.fromPos,
            Level.NETHER, end.toPos, 1, end.rotation, end.orientation, 2, 3);
        assertNull(animation.getCurrentState(150, Level.NETHER, Level.NETHER));
    }

    @Test void ordinaryMotionStillInterpolatesAndFinishesAtTheExactTarget() {
        var animation = animation();
        var halfway = animation.getCurrentState(150, Level.NETHER, Level.OVERWORLD);
        assertEquals(1, halfway.fromPos.x, 1e-9);
        assertEquals(21, halfway.toPos.x, 1e-9);
        assertSame(animation.toState, animation.getCurrentState(201, Level.NETHER, Level.OVERWORLD));
    }

    @Test void aFreshAnimationWorksAfterACompletedDimensionHandoff() {
        var start = state(0); var end = state(2);
        var animation = new ClientPortalAnimationManagement.RunningDefaultAnimation(
            new PortalState(Level.NETHER, start.fromPos, Level.NETHER, start.toPos,
                1, start.rotation, start.orientation, 2, 3),
            new PortalState(Level.NETHER, end.fromPos, Level.NETHER, end.toPos,
                1, end.rotation, end.orientation, 2, 3), 100, 200, TimingFunction.linear, false);
        assertEquals(1, animation.getCurrentState(150, Level.NETHER, Level.NETHER).fromPos.x, 1e-9);
    }
}
