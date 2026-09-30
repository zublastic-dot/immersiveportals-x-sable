package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the production mixin method and the installed DH rolling-average implementation. */
class DhCameraSpeedHistoryTest {
    private final Class<?> type;
    private final Object instance;
    private final Field position, time;
    DhCameraSpeedHistoryTest() throws Exception {
        type=Class.forName("qouteall.imm_ptl.core.compat.mixin.dh.MixinDhClientApi");
        instance=type.getConstructor().newInstance();
        position=type.getDeclaredField("lastCameraPosForSpeedCheck");position.setAccessible(true);
        time=type.getDeclaredField("msSinceLastSpeedCheck");time.setAccessible(true);
    }
    private DhCameraSpeedHistory history(DhVec3d p,long millis) throws Exception {
        position.set(instance,p);time.setLong(instance,millis);return (DhCameraSpeedHistory)instance;
    }
    private RollingAverage average(double speed) {
        var average=new RollingAverage(40); for(int i=0;i<40;i++) average.add(speed);return average;
    }
    @ParameterizedTest @ValueSource(ints={-1,1})
    void dimensionJumpReproducesTwoSecondFalseSpeedAndRebaseRetainsWalking(int direction) throws Exception {
        var previous=new DhVec3d(24,149,200);
        double dx=direction*-11,dy=direction*-100,dz=direction*-178;
        var current=new DhVec3d(previous.x+dx,previous.y+dy,previous.z+dz+.2);
        var broken=average(4);
        broken.add(current.getDistance(previous)/.05);
        assertTrue(broken.getAverage()>100,"Raw dimension coordinates trigger DH's strongest speed reduction");
        for(int i=0;i<39;i++) { broken.add(4);assertTrue(broken.getAverage()>100); }
        broken.add(4);assertEquals(4,broken.getAverage(),1e-9,"40 samples at ~50 ms recover after ~2 seconds");
        var fixed=average(4);
        assertTrue(history(previous,1000).ip_rebaseCameraSpeed(p->new DhVec3d(p.x+dx,p.y+dy,p.z+dz)));
        assertEquals(1000,time.getLong(instance));
        fixed.add(current.getDistance((DhVec3d)position.get(instance))/.05);
        assertEquals(4,fixed.getAverage(),1e-9,"Walking remains below DH's 10-block/s adaptation threshold");
        assertEquals(149,previous.y,"Original sample object remains intact");
    }
    @Test void realFastMovementStillEntersTheOriginalDhAverage() throws Exception {
        var original=new DhVec3d(1,2,3);var average=average(60);
        assertTrue(history(original,1000).ip_rebaseCameraSpeed(p->new DhVec3d(p.x+1000,p.y-100,p.z)));
        var current=new DhVec3d(1001,-98,6);
        average.add(current.getDistance((DhVec3d)position.get(instance))/.05);
        assertEquals(60,average.getAverage(),1e-9);
        assertEquals(41,average.getLifetimeCount());assertEquals(1000,time.getLong(instance));
    }
    @Test void rotationsScalingAndConsecutiveCrossingsTransformTheSameReferencePoint() throws Exception {
        var h=history(new DhVec3d(1,2,3),1000);
        assertTrue(h.ip_rebaseCameraSpeed(p->new DhVec3d(-2*p.z+100,2*p.y,2*p.x)));
        var p=(DhVec3d)position.get(instance);assertEquals(94,p.x);assertEquals(4,p.y);assertEquals(2,p.z);
        assertTrue(h.ip_rebaseCameraSpeed(q->new DhVec3d(q.z/2,q.y/2,(100-q.x)/2)));
        p=(DhVec3d)position.get(instance);assertEquals(1,p.x);assertEquals(2,p.y);assertEquals(3,p.z);
        assertEquals(1000,time.getLong(instance));
    }
    @Test void uninitializedAndInvalidTransformsCannotPolluteTheSample() throws Exception {
        var original=new DhVec3d(1,2,3);
        assertFalse(history(original,0).ip_rebaseCameraSpeed(p->{fail("No initialized sample");return p;}));
        var h=history(original,1000);
        assertFalse(h.ip_rebaseCameraSpeed(p->{p.x=Double.NaN;return p;}));assertSame(original,position.get(instance));
        assertFalse(h.ip_rebaseCameraSpeed(p->null));assertSame(original,position.get(instance));
        var failure=new IllegalStateException("bad transform");
        assertSame(failure,assertThrows(IllegalStateException.class,()->h.ip_rebaseCameraSpeed(p->{p.x=50;throw failure;})));
        assertSame(original,position.get(instance));assertEquals(1,original.x);assertEquals(1000,time.getLong(instance));
    }
}
