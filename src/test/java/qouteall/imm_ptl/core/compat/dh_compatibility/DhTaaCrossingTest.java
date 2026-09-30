package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DhTaaCrossingTest {
    @Test void ticketIsExactOneShotAndNestedCrossingsKeepTheirOrderedPath() {
        var crossing = new DhTaaCrossing(); var a = UUID.randomUUID(); var b = UUID.randomUUID();
        crossing.crossed("nether", "overworld", a, 10, 100);
        assertEquals(new DhTaaCrossing.Key("nether","overworld",List.of(a)), crossing.take("overworld",11,200));
        assertNull(crossing.take("overworld",11,201));
        crossing.crossed("nether","overworld",a,12,300);
        crossing.crossed("overworld","end",b,12,301);
        assertEquals(new DhTaaCrossing.Key("nether","end",List.of(a,b)),crossing.take("end",13,400));
    }
    @Test void wrongDimensionStaleAndClockRollbackTicketsAreRejected() {
        var crossing = new DhTaaCrossing(); var portal = UUID.randomUUID();
        crossing.crossed("a","b",portal,10,100);
        assertNull(crossing.take("c",11,200));
        crossing.crossed("a","b",portal,10,100);
        assertNull(crossing.take("b",12,200));
        crossing.crossed("a","b",portal,10,100);
        assertNull(crossing.take("b",11,500_000_100));
        crossing.crossed("a","b",portal,10,100);
        assertNull(crossing.take("b",11,99));
        crossing.crossed("a","b",portal,10,100); crossing.clear();
        assertNull(crossing.take("b",11,200));
    }
    @Test void onlyCompletedCurrentImageCanBeDonatedWithItsOwnCameraAndPhase() {
        var h = new DhTaaHistory(); var p = new Matrix4f().perspective(1,1,.1f,1000);
        var v = new Matrix4f().rotateY(.1f); var camera = new Vector3d(30,40,50);
        h.begin(1,100,p,v,camera); assertNull(h.snapshot()); h.complete();
        var old=h.snapshot();
        h.begin(2,200,p,v,new Vector3d(31,40,50)); assertNull(h.snapshot()); h.complete();
        var current=h.snapshot(); assertEquals(1,current.phase());
        assertEquals(new Vector3d(31,40,50),current.camera());
        assertEquals(new Matrix4f(p).mul(v),current.combined());
        assertEquals(camera,old.camera());
        assertTrue(current.matches(3,300,p,v,new Vector3d(32,40,50)));
        assertFalse(current.matches(4,300,p,v,camera));
        assertFalse(current.matches(3,300,new Matrix4f(),v,camera));
        assertFalse(current.matches(3,300,p,new Matrix4f().rotateY(1),camera));
        assertFalse(current.matches(3,300,p,v,new Vector3d(100,40,50)));
        h.invalidate(); assertNull(h.snapshot());
    }
}
