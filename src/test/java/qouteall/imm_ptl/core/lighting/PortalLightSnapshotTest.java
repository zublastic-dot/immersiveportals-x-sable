package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightSnapshot.*;

class PortalLightSnapshotTest {
    Sample room(Pos p) {
        boolean air = p.x() >= 1 && p.x() <= 4 && p.y() >= 1 && p.y() <= 8 && p.z() >= 1 && p.z() <= 10;
        return new Sample(air ? Cell.OPEN : Cell.CLOSED, new Light(0, 0));
    }
    Sample source(Pos p) { return new Sample(Cell.OPEN, new Light(15, 0)); }
    Map<Pos, Pos> aperture() {
        var map = new HashMap<Pos, Pos>();
        for (int y = 1; y <= 8; y++) for (int z = 1; z <= 10; z++) map.put(new Pos(1,y,z), new Pos(100,y,z));
        return map;
    }
    Update live() { return update(this::room, this::source, aperture(), new Pos(1,0,0), null); }

    @Test void destinationChunkUnloadDoesNotChangePreviouslyVerifiedField() {
        var live = live();
        assertTrue(live.field().available());
        var distant = update(p -> Sample.UNKNOWN, this::source, aperture(), new Pos(1,0,0), live.snapshot());
        assertTrue(distant.usedCache());
        assertEquals(live.field(), distant.field());
        assertEquals(new Light(0,0), distant.snapshot().geometry().get(new Pos(4,4,5)).light());
    }
    @Test void unloadWithoutAnyProofDoesNotInventARoom() {
        var result = update(p -> Sample.UNKNOWN, this::source, aperture(), new Pos(1,0,0), null);
        assertFalse(result.field().available()); assertNull(result.snapshot());
    }
    @Test void cachedGeometryStillUsesChangedIncomingLight() {
        var result = update(p -> Sample.UNKNOWN, p -> new Sample(Cell.OPEN,new Light(0,9)), aperture(), new Pos(1,0,0), live().snapshot());
        assertEquals(new Light(0,5),result.field().cells().get(new Pos(4,4,5)));
    }
    @Test void reloadWithOpenRoofChangesOnlyLocalExposure() {
        var result = update(p -> p.equals(new Pos(2,9,5)) || p.y()>9 ? new Sample(Cell.OPEN,new Light(0,0)) : room(p),
            this::source, aperture(), new Pos(1,0,0), live().snapshot());
        assertTrue(result.field().available()); assertNotNull(result.snapshot());
        assertTrue(result.field().replacement().get(new Pos(2,8,5))<1);
        assertTrue(result.field().replacement().get(new Pos(1,1,1))>.9);
    }
    @Test void partialReloadUpdatesKnownGeometryWithoutDiscardingCachedCells() {
        var result = update(p -> p.equals(new Pos(2,4,5)) ? new Sample(Cell.CLOSED,new Light(0,0)) : Sample.UNKNOWN,
            this::source, aperture(), new Pos(1,0,0), live().snapshot());
        assertTrue(result.field().available()); assertTrue(result.usedCache());
        assertFalse(result.field().cells().containsKey(new Pos(2,4,5)));
        assertEquals(Cell.CLOSED,result.snapshot().geometry().get(new Pos(2,4,5)).cell());
        assertEquals(1f,result.field().replacement().get(new Pos(1,1,1)));
    }
    @Test void loadedLocalLampReplacesCachedNativeLight() {
        Pos lamp = new Pos(2,4,5);
        var result = update(p -> p.equals(lamp) ? new Sample(Cell.OPEN,new Light(0,14)) : room(p),
            this::source, aperture(), new Pos(1,0,0), live().snapshot());
        assertTrue(result.field().available()); assertFalse(result.usedCache());
        assertEquals(14,result.snapshot().geometry().get(lamp).light().block());
    }
    @Test void movedApertureDoesNotReuseOldGeometry() {
        var moved = new HashMap<Pos,Pos>(); aperture().forEach((p,s) -> moved.put(p.add(1,0,0),s));
        var result = update(p -> Sample.UNKNOWN, this::source, moved, new Pos(1,0,0), live().snapshot());
        assertFalse(result.field().available()); assertNull(result.snapshot());
    }
    @Test void completeUnloadRetainsOnlyProvenSamples() {
        var result = update(p -> Sample.UNKNOWN, p -> Sample.UNKNOWN, aperture(), new Pos(1,0,0), live().snapshot());
        assertTrue(result.field().available()); assertTrue(result.usedCache());
        assertEquals(320,result.field().cells().size());
        assertFalse(result.snapshot().geometry().containsKey(new Pos(4,40,5)));
    }
    @Test void openedRoomSurvivesUnloadAndResealingRestoresClosedLighting() {
        Reader opened=p->p.x()>5 || p.equals(new Pos(5,4,5)) ? new Sample(Cell.OPEN,new Light(0,0)) : room(p);
        var initial=update(opened,this::source,aperture(),new Pos(1,0,0),null);
        assertTrue(initial.field().available());
        var unloaded=update(p->Sample.UNKNOWN,p->Sample.UNKNOWN,aperture(),new Pos(1,0,0),initial.snapshot());
        assertTrue(unloaded.usedCache());assertEquals(initial.field(),unloaded.field());
        var resealed=update(this::room,this::source,aperture(),new Pos(1,0,0),unloaded.snapshot());
        assertEquals(live().field(),resealed.field());
    }
    @Test void blockedSourceIsDarkRatherThanDisablingTheField() {
        var result=update(this::room,p->new Sample(Cell.CLOSED,new Light(15,15)),aperture(),new Pos(1,0,0),null);
        assertTrue(result.field().available());
        assertTrue(result.field().cells().values().stream().allMatch(l->l.sky()==0&&l.block()==0));
    }
}
