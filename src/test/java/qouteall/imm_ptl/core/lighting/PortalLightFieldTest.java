package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalLightFieldTest {
    Cell room(Pos p) { return p.x()>=1&&p.x()<=4&&p.y()>=1&&p.y()<=8&&p.z()>=1&&p.z()<=10?Cell.OPEN:Cell.CLOSED; }
    Map<Pos,Light> aperture(int sky,int block) {
        var seeds=new HashMap<Pos,Light>();
        for(int y=1;y<=8;y++) for(int z=1;z<=10;z++) seeds.put(new Pos(1,y,z),new Light(sky,block));
        return seeds;
    }
    @Test void enclosedRoomUsesApertureAndAttenuatesWithDepth() {
        var result=solve(this::room,aperture(14,0),4096,32);
        assertTrue(result.enclosed());assertEquals(320,result.cells().size());
        for(int x=1;x<=4;x++) assertEquals(new Light(15-x,0),result.cells().get(new Pos(x,4,5)));
        assertFalse(result.cells().containsKey(new Pos(2,9,5)),"roof/source side must not receive room correction");
    }
    @Test void openingOneRoofBlockToOutsideInvalidatesAmbientReplacement() {
        var result=solve(p -> p.y()>8 || p.equals(new Pos(2,9,5))?Cell.OPEN:room(p),aperture(14,0),4096,32);
        assertFalse(result.enclosed());assertTrue(result.cells().isEmpty());
    }
    @Test void unknownBoundaryCannotBeTreatedAsOpaque() {
        var result=solve(p -> p.equals(new Pos(2,9,5))?Cell.UNKNOWN:room(p),aperture(14,0),4096,32);
        assertEquals("unloaded boundary",result.reason());assertTrue(result.cells().isEmpty());
    }
    @Test void darkApertureStillDiscoversWholeEnclosure() {
        var result=solve(this::room,aperture(0,0),4096,32);
        assertTrue(result.enclosed());assertEquals(320,result.cells().size());
        assertTrue(result.cells().values().stream().allMatch(l -> l.sky()==0&&l.block()==0));
    }
    @Test void opaquePartitionStopsImportedLight() {
        var result=solve(p -> p.x()==3?Cell.CLOSED:room(p),aperture(14,14),4096,32);
        assertTrue(result.enclosed());assertEquals(160,result.cells().size());
        assertFalse(result.cells().containsKey(new Pos(4,4,5)));
    }
    @Test void multipleSourcesKeepStrongestWithoutCreatingEnergy() {
        var seeds=aperture(9,0);seeds.put(new Pos(4,4,5),new Light(0,15));
        var result=solve(this::room,seeds,4096,32);
        assertEquals(new Light(6,15),result.cells().get(new Pos(4,4,5)));
        assertEquals(new Light(9,12),result.cells().get(new Pos(1,4,5)));
    }
    @Test void overBudgetAndBlockedAperturesFailClosed() {
        assertFalse(solve(this::room,aperture(14,0),10,32).enclosed());
        assertFalse(solve(this::room,aperture(14,0),4096,4).enclosed());
        assertEquals("blocked aperture",solve(p -> Cell.CLOSED,aperture(14,0),4096,32).reason());
        assertFalse(solve(this::room,Map.of(),4096,32).enclosed());
    }
    @Test void negativeAndLargeWorldCoordinatesDoNotChangeTransport() {
        Pos offset=new Pos(-20_000_000,-60,20_000_000);
        var seeds=new HashMap<Pos,Light>();aperture(14,0).forEach((p,l)->seeds.put(p.add(offset.x(),offset.y(),offset.z()),l));
        var result=solve(p -> room(p.add(-offset.x(),-offset.y(),-offset.z())),seeds,4096,32);
        assertTrue(result.enclosed());assertEquals(new Light(11,0),result.cells().get(new Pos(4,4,5).add(offset.x(),offset.y(),offset.z())));
    }
}
