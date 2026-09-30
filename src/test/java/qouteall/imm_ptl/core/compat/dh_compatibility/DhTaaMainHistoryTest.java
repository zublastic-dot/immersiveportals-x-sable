package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DhTaaMainHistoryTest {
    private DhTaaHistory.Snapshot snapshot() {
        return new DhTaaHistory.Snapshot(20,100,7,new Matrix4f(),new Matrix4f(),new Vector3d(10,30,40));
    }
    @Test void backingAwayFromMainLandscapeAdmitsOnlyTheLinkedReversePortal() {
        var main=new DhTaaMainHistory("overworld",1,800,600,false,snapshot());
        var reverse=UUID.randomUUID(); var other=UUID.randomUUID();
        var key=main.returnKey("overworld","nether",reverse,20,101);
        assertEquals(new DhTaaCrossing.Key("nether","overworld",List.of(reverse)),key);
        assertNotEquals(new DhTaaCrossing.Key("nether","overworld",List.of(other)),key);
        assertNotEquals(new DhTaaCrossing.Key("nether","overworld",List.of(reverse,other)),key);
        assertNull(main.returnKey("end","nether",reverse,20,101));
        assertNull(main.returnKey("overworld","nether",null,20,101));
    }
    @Test void staleClockRollbackAndDisabledAaCannotDonateMainHistory() {
        var main=new DhTaaMainHistory("overworld",1,800,600,false,snapshot()); var id=UUID.randomUUID();
        assertNull(main.returnKey("overworld","nether",id,22,101));
        assertNull(main.returnKey("overworld","nether",id,20,500_000_100));
        assertNull(main.returnKey("overworld","nether",id,20,99));
        var disabled=new DhTaaHistory.Snapshot(20,100,-1,new Matrix4f(),new Matrix4f(),new Vector3d());
        assertNull(new DhTaaMainHistory("overworld",1,800,600,false,disabled).returnKey("overworld","nether",id,20,101));
    }
    @Test void restoredMainSnapshotContinuesPhaseAndCameraOnTheFirstPortalFrame() {
        var h=new DhTaaHistory(); var s=snapshot(); h.restoreCompleted(s);
        assertTrue(h.begin(21,200,new Matrix4f(),new Matrix4f(),new Vector3d(10,30,40.1)));
        assertTrue(h.valid); assertEquals(0,h.phase()); assertEquals(s.camera(),h.previousCamera);
        assertEquals(s.combined(),h.previousCombined);
        assertFalse(h.begin(21,201,new Matrix4f(),new Matrix4f(),new Vector3d(10,30,40.1)));
        assertEquals(0,h.phase()); h.invalidate(); assertNull(h.snapshot());
    }
}
