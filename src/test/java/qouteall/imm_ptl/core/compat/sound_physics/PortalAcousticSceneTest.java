package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.teleportation.PortalSoundPath;
import static org.junit.jupiter.api.Assertions.*;

class PortalAcousticSceneTest {
    private static final PortalSoundPath.Frame FRAME = new PortalSoundPath.Frame(Vec3.ZERO,
        new Vec3(100, 50, 20), new Vec3(1,0,0), new Vec3(0,1,0),
        new Vec3(0,0,1), new Vec3(0,1,0), 2, 3);

    @Test void raySplitsAtTheTransformedOpeningBothDirections() {
        Vec3 a = FRAME.destination().add(-5, 1, 1), b = FRAME.destination().add(5, 1, 1);
        var forward = PortalAcousticScene.split(FRAME, a, b);
        var reverse = PortalAcousticScene.split(FRAME, b, a);
        assertNotNull(forward); assertTrue(forward.open());
        assertEquals(FRAME.destination().add(0,1,1), forward.point());
        assertEquals(forward, reverse);
    }

    @Test void rayCannotLeakThroughTheInfinitePlaneOutsideTheAperture() {
        var horizontal = PortalAcousticScene.split(FRAME, FRAME.destination().add(-5,0,2.01), FRAME.destination().add(5,0,2.01));
        var vertical = PortalAcousticScene.split(FRAME, FRAME.destination().add(-5,3.01,0), FRAME.destination().add(5,3.01,0));
        assertFalse(horizontal.open()); assertFalse(vertical.open());
    }

    @Test void sameWorldHalfAndParallelRaysDoNotChangeWorlds() {
        assertNull(PortalAcousticScene.split(FRAME, FRAME.destination().add(-5,0,0), FRAME.destination().add(-3,1,0)));
        assertNull(PortalAcousticScene.split(FRAME, FRAME.destination().add(0,0,0), FRAME.destination().add(0,1,0)));
    }

    @Test void sourceAndReceivingTransformsRemainInverseAtLargeCoordinates() {
        Vec3 point = new Vec3(20481032.125, 125.75, 20483073.5);
        var frame = new PortalSoundPath.Frame(point, FRAME.destination(), FRAME.sourceU(), FRAME.sourceV(),
            FRAME.destinationU(), FRAME.destinationV(), 2, 3);
        Vec3 ray = point.add(1.25, -.75, 3.5);
        assertTrue(frame.inverse(frame.transform(ray)).distanceTo(ray) < 1e-7);
        Vec3 normal = new Vec3(.6, .8, 0);
        assertTrue(frame.inverseVector(frame.transformVector(normal)).distanceTo(normal) < 1e-7);
    }
}
