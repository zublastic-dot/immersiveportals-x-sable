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
    Update live() { return update(this::room, this::source, aperture(), null); }

    @Test void destinationChunkUnloadDoesNotChangePreviouslyVerifiedField() {
        var live = live();
        assertTrue(live.field().enclosed());
        var distant = update(p -> Sample.UNKNOWN, this::source, aperture(), live.snapshot());
        assertTrue(distant.usedCache());
        assertEquals(live.field(), distant.field());
        assertEquals(new Light(0,0), distant.snapshot().geometry().get(new Pos(4,4,5)).light());
    }
    @Test void unloadWithoutAnyProofDoesNotInventARoom() {
        var result = update(p -> Sample.UNKNOWN, this::source, aperture(), null);
        assertFalse(result.field().enclosed()); assertNull(result.snapshot());
    }
    @Test void cachedGeometryStillUsesChangedIncomingLight() {
        var result = update(p -> Sample.UNKNOWN, p -> new Sample(Cell.OPEN,new Light(0,9)), aperture(), live().snapshot());
        assertEquals(new Light(0,5),result.field().cells().get(new Pos(4,4,5)));
    }
    @Test void reloadWithOpenRoofInvalidatesSnapshot() {
        var result = update(p -> p.equals(new Pos(2,9,5)) || p.y()>9 ? new Sample(Cell.OPEN,new Light(0,0)) : room(p),
            this::source, aperture(), live().snapshot());
        assertFalse(result.field().enclosed()); assertNull(result.snapshot());
    }
    @Test void partialReloadCannotHideChangedGeometryBehindCachedWalls() {
        var result = update(p -> p.equals(new Pos(2,4,5)) ? new Sample(Cell.CLOSED,new Light(0,0)) : Sample.UNKNOWN,
            this::source, aperture(), live().snapshot());
        assertEquals("changed incomplete geometry",result.field().reason()); assertNull(result.snapshot());
    }
    @Test void loadedLocalLampReplacesCachedNativeLight() {
        Pos lamp = new Pos(2,4,5);
        var result = update(p -> p.equals(lamp) ? new Sample(Cell.OPEN,new Light(0,14)) : room(p),
            this::source, aperture(), live().snapshot());
        assertTrue(result.field().enclosed()); assertFalse(result.usedCache());
        assertEquals(14,result.snapshot().geometry().get(lamp).light().block());
    }
    @Test void movedApertureDoesNotReuseOldGeometry() {
        var moved = new HashMap<Pos,Pos>(); aperture().forEach((p,s) -> moved.put(p.add(1,0,0),s));
        var result = update(p -> Sample.UNKNOWN, this::source, moved, live().snapshot());
        assertFalse(result.field().enclosed()); assertNull(result.snapshot());
    }
    @Test void completeUnloadRetainsOnlyProvenSamples() {
        var result = update(p -> Sample.UNKNOWN, p -> Sample.UNKNOWN, aperture(), live().snapshot());
        assertTrue(result.field().enclosed()); assertTrue(result.usedCache());
        assertEquals(320,result.field().cells().size());
        assertFalse(result.snapshot().geometry().containsKey(new Pos(4,40,5)));
    }
}
