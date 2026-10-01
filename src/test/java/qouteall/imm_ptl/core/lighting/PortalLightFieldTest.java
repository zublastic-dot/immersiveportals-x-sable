package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalLightFieldTest {
    static final Pos INWARD=new Pos(1,0,0), HOLE=new Pos(2,9,5);
    Cell room(Pos p) { return p.x()>=1&&p.x()<=4&&p.y()>=1&&p.y()<=8&&p.z()>=1&&p.z()<=10?Cell.OPEN:Cell.CLOSED; }
    Map<Pos,Light> aperture(int sky,int block) {
        var seeds=new HashMap<Pos,Light>();
        for(int y=1;y<=8;y++) for(int z=1;z<=10;z++) seeds.put(new Pos(1,y,z),new Light(sky,block));
        return seeds;
    }
    Result field(World world) { return solve(world,aperture(14,0),INWARD,32); }
    World roofOpening(int width) {
        return p -> p.y()>9 || (p.y()==9&&p.x()==2&&Math.abs(p.z()-5)<=width)?Cell.OPEN:room(p);
    }
    @Test void enclosedRoomUsesApertureAndAttenuatesWithDepth() {
        var result=field(this::room);
        assertTrue(result.available());assertEquals(320,result.cells().size());
        for(int x=1;x<=4;x++) assertEquals(new Light(15-x,0),result.cells().get(new Pos(x,4,5)));
        assertTrue(result.replacement().values().stream().allMatch(w->w==1f));
        assertFalse(result.cells().containsKey(new Pos(2,9,5)),"outside roof must not receive interior correction");
    }
    @Test void oneHoleAdmitsLocalAmbientWithoutDiscardingRoom() {
        var result=field(roofOpening(0));
        assertTrue(result.available());
        assertTrue(result.cells().keySet().containsAll(field(this::room).cells().keySet()));
        float near=result.replacement().get(new Pos(2,8,5));
        float far=result.replacement().get(new Pos(1,1,1));
        assertTrue(near>0 && near<1,"hole mixes rather than replaces all illumination");
        assertTrue(far>near,"small opening must affect nearby cells more than a distant corner");
        double retained=field(this::room).cells().keySet().stream().mapToDouble(p->result.replacement().get(p)).average().orElseThrow();
        assertTrue(retained>.85,"one hole must not recolor the whole room");
        assertEquals(new Light(11,0),result.cells().get(new Pos(4,4,5)),"portal illumination still propagates");
    }
    @Test void largerOpeningAdmitsMoreAmbientAtTheSamePoints() {
        var one=field(roofOpening(0));var larger=field(roofOpening(3));
        boolean increased=false;
        for(Pos p:field(this::room).cells().keySet()) {
            assertTrue(larger.replacement().get(p)<=one.replacement().get(p)+1e-6);
            increased |= larger.replacement().get(p)<one.replacement().get(p)-.01;
        }
        assertTrue(increased);
    }
    @Test void ownersBackWallHolePreservesMostOfTheRoomCorrection() {
        Pos hole=new Pos(5,4,5);
        var result=field(p->p.x()>5 || p.equals(hole)?Cell.OPEN:room(p));
        assertTrue(result.available());
        float near=result.replacement().get(new Pos(4,4,5));
        assertTrue(near>0 && near<1);
        assertTrue(result.replacement().get(new Pos(1,1,1))>near);
        double retained=field(this::room).cells().keySet().stream().mapToDouble(p->result.replacement().get(p)).average().orElseThrow();
        assertTrue(retained>.85,"back-wall hole must not reset the entire room");
    }
    @Test void resealingRestoresOriginalLightingWithoutHistory() {
        var before=field(this::room);
        assertNotEquals(before,field(roofOpening(0)));
        assertEquals(before,field(this::room));
    }
    @Test void unknownBoundaryIsLocalUncertaintyNotAWholeRoomSwitch() {
        var result=field(p->p.equals(HOLE)?Cell.UNKNOWN:room(p));
        assertTrue(result.available());assertEquals(320,result.cells().size());
        assertTrue(result.replacement().get(new Pos(2,8,5))<1);
        assertTrue(result.replacement().get(new Pos(1,1,1))>.9);
        assertFalse(result.cells().containsKey(HOLE),"unknown voxels are never invented as air");
    }
    @Test void secondOpaqueLayerStillBlocksAnOpeningInFirstLayer() {
        var result=field(p->p.equals(HOLE)?Cell.OPEN:room(p));
        assertTrue(result.available());
        for(Pos p:field(this::room).cells().keySet()) assertEquals(1f,result.replacement().get(p));
    }
    @Test void darkApertureStillSupportsRoomAmbientCorrection() {
        var result=solve(this::room,aperture(0,0),INWARD,32);
        assertTrue(result.available());assertEquals(320,result.cells().size());
        assertTrue(result.cells().values().stream().allMatch(l->l.sky()==0&&l.block()==0));
        assertTrue(result.replacement().values().stream().allMatch(w->w==1f));
    }
    @Test void opaquePartitionStopsImportedLightAndCorrection() {
        var result=field(p->p.x()==3?Cell.CLOSED:room(p));
        assertTrue(result.available());assertEquals(160,result.cells().size());
        assertFalse(result.cells().containsKey(new Pos(4,4,5)));
    }
    @Test void apertureSamplesKeepStrongestLightWithoutCreatingEnergy() {
        var seeds=aperture(9,0);seeds.put(new Pos(1,4,5),new Light(9,15));
        var result=solve(this::room,seeds,INWARD,32);
        assertEquals(new Light(6,12),result.cells().get(new Pos(4,4,5)));
        assertEquals(new Light(9,15),result.cells().get(new Pos(1,4,5)));
    }
    @Test void openTerrainHasBoundedSmoothSupport() {
        var result=field(p->Cell.OPEN);
        assertTrue(result.available());assertTrue(result.cells().size()<=16*32*32);
        assertTrue(result.replacement().values().stream().allMatch(w->w>=0&&w<=1));
        assertEquals(0f,result.replacement().get(new Pos(16,4,5)));
        assertTrue(result.replacement().get(new Pos(1,4,5))>0);
    }
    @Test void unsupportedOrUnknownAperturesDoNotInventGeometry() {
        assertFalse(solve(this::room,aperture(14,0),INWARD,4).available());
        assertFalse(field(p->Cell.CLOSED).available());
        assertFalse(field(p->Cell.UNKNOWN).available());
        assertFalse(solve(this::room,Map.of(),INWARD,32).available());
        assertFalse(solve(this::room,aperture(14,0),new Pos(1,1,0),32).available());
    }
    @Test void negativeAndLargeWorldCoordinatesDoNotChangeTransport() {
        Pos offset=new Pos(-20_000_000,-60,20_000_000);
        var seeds=new HashMap<Pos,Light>();aperture(14,0).forEach((p,l)->seeds.put(p.add(offset.x(),offset.y(),offset.z()),l));
        var reference=field(roofOpening(0));
        var result=solve(p->roofOpening(0).cell(p.add(-offset.x(),-offset.y(),-offset.z())),seeds,INWARD,32);
        assertTrue(result.available());
        for(Pos p:reference.cells().keySet()) {
            Pos shifted=p.add(offset.x(),offset.y(),offset.z());
            assertEquals(reference.cells().get(p),result.cells().get(shifted));
            assertEquals(reference.replacement().get(p),result.replacement().get(shifted));
        }
    }
    @Test void oppositePortalDirectionHasTheSameLighting() {
        var seeds=new HashMap<Pos,Light>();aperture(14,0).forEach((p,l)->seeds.put(new Pos(-p.x(),p.y(),p.z()),l));
        var reference=field(roofOpening(0));
        var result=solve(p->roofOpening(0).cell(new Pos(-p.x(),p.y(),p.z())),seeds,new Pos(-1,0,0),32);
        assertTrue(result.available());
        for(Pos p:reference.cells().keySet()) {
            Pos mirrored=new Pos(-p.x(),p.y(),p.z());
            assertEquals(reference.cells().get(p),result.cells().get(mirrored));
            assertEquals(reference.replacement().get(p),result.replacement().get(mirrored));
        }
    }
}
