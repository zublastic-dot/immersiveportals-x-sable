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
    @Test void lightOnlyRefreshReadsEverySourceButNeverReadsTargetGeometry() {
        var before=live();
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var refreshed=update(p->{fail("unchanged topology must not read target geometry");return Sample.UNKNOWN;},
            p->{reads.incrementAndGet();return new Sample(Cell.OPEN,new Light(0,12));},
            aperture(),new Pos(1,0,0),before.snapshot(),false);
        assertEquals(aperture().size(),reads.get());
        assertSame(before.snapshot().topology(),refreshed.snapshot().topology());
        assertSame(before.snapshot().geometry(),refreshed.snapshot().geometry());
        assertSame(before.field().replacement(),refreshed.field().replacement());
        assertEquals(new Light(0,8),refreshed.field().cells().get(new Pos(4,4,5)));
    }
    @Test void loweredAndRemovedSourcesDoNotLeavePreviousBrightValues() {
        Reader bright=p->new Sample(Cell.OPEN,new Light(0,15));
        var before=update(this::room,bright,aperture(),new Pos(1,0,0),null,false);
        var dimmed=update(p->Sample.UNKNOWN,p->new Sample(Cell.OPEN,new Light(0,7)),
            aperture(),new Pos(1,0,0),before.snapshot(),false);
        assertEquals(new Light(0,3),dimmed.field().cells().get(new Pos(4,4,5)));
        var removed=update(p->Sample.UNKNOWN,p->new Sample(Cell.OPEN,new Light(0,0)),
            aperture(),new Pos(1,0,0),dimmed.snapshot(),false);
        assertTrue(removed.field().cells().values().stream().allMatch(l->l.equals(new Light(0,0))));
        assertSame(before.snapshot().topology(),removed.snapshot().topology());
    }
    @Test void changedSourceOpacityTakesEffectWithoutTopologyInvalidation() {
        var before=live();
        var blocked=update(p->Sample.UNKNOWN,p->new Sample(Cell.CLOSED,new Light(15,15)),
            aperture(),new Pos(1,0,0),before.snapshot(),false);
        assertTrue(blocked.field().available());
        assertTrue(blocked.field().cells().values().stream().allMatch(l->l.equals(new Light(0,0))));
        assertSame(before.snapshot().topology(),blocked.snapshot().topology());
        var opened=update(p->Sample.UNKNOWN,this::source,aperture(),new Pos(1,0,0),blocked.snapshot(),false);
        assertEquals(before.field(),opened.field());
    }
    @Test void unchangedSourcesReuseResultAndBothMaps() {
        var before=live();
        var after=update(p->Sample.UNKNOWN,this::source,aperture(),new Pos(1,0,0),before.snapshot(),false);
        assertSame(before.field(),after.field());
        assertSame(before.field().cells(),after.field().cells());
        assertSame(before.field().replacement(),after.field().replacement());
    }
    @Test void dirtyUnchangedOccupancyReusesTopologyWhileRefreshingMeasuredLight() {
        var before=live();
        var after=update(p->new Sample(room(p).cell(),new Light(0,14)),this::source,
            aperture(),new Pos(1,0,0),before.snapshot(),true);
        assertSame(before.snapshot().topology(),after.snapshot().topology());
        assertSame(before.field(),after.field());
        assertEquals(14,after.snapshot().geometry().get(new Pos(4,4,5)).light().block());
    }
    @Test void dirtyOpeningAndResealingRebuildVisibilityLocally() {
        var before=live();
        Reader opened=p->p.x()>5 || p.equals(new Pos(5,4,5)) ? new Sample(Cell.OPEN,new Light(0,0)) : room(p);
        var hole=update(opened,this::source,aperture(),new Pos(1,0,0),before.snapshot(),true);
        assertNotSame(before.snapshot().topology(),hole.snapshot().topology());
        assertTrue(hole.field().replacement().get(new Pos(4,4,5))<1);
        assertTrue(hole.field().replacement().get(new Pos(1,1,1))>.9);
        var sealed=update(this::room,this::source,aperture(),new Pos(1,0,0),hole.snapshot(),true);
        assertNotSame(hole.snapshot().topology(),sealed.snapshot().topology());
        assertEquals(before.field(),sealed.field());
    }
    @Test void dirtyUnloadPreservesTopologyAndReloadReplacesKnownCells() {
        var before=live();
        var unloaded=update(p->Sample.UNKNOWN,p->Sample.UNKNOWN,aperture(),new Pos(1,0,0),before.snapshot(),true);
        assertTrue(unloaded.usedCache());
        assertSame(before.snapshot().topology(),unloaded.snapshot().topology());
        assertSame(before.field(),unloaded.field());
        var reload=update(p->p.x()==3 ? new Sample(Cell.CLOSED,new Light(0,0)) : Sample.UNKNOWN,
            this::source,aperture(),new Pos(1,0,0),unloaded.snapshot(),true);
        assertNotSame(unloaded.snapshot().topology(),reload.snapshot().topology());
        assertFalse(reload.field().cells().containsKey(new Pos(4,4,5)));
    }
    @Test void snapshotContainsHaloDependenciesEvenWhenTheyAreNotReachable() {
        var snapshot=live().snapshot();
        // Aperture Y1..8/Z1..10, halo padding4, inward depth16, then grid's extra cell.
        assertTrue(snapshot.geometry().containsKey(new Pos(0,-4,-4)));
        assertTrue(snapshot.geometry().containsKey(new Pos(17,13,15)));
        assertFalse(snapshot.field().cells().containsKey(new Pos(17,13,15)));
    }
    @Test void partialUnknownSourceDoesNotShrinkApertureOrAmbientVisibility() {
        Reader partial=p->p.y()==4 && p.z()==5 ? source(p) : Sample.UNKNOWN;
        var observed=update(this::room,partial,aperture(),new Pos(1,0,0),null,false);
        assertTrue(observed.field().available());
        assertEquals(live().field().cells().keySet(),observed.field().cells().keySet());
        assertEquals(live().field().replacement(),observed.field().replacement());
        var complete=update(p->Sample.UNKNOWN,this::source,aperture(),new Pos(1,0,0),observed.snapshot(),false);
        assertSame(observed.snapshot().topology(),complete.snapshot().topology());
        assertEquals(live().field(),complete.field());
    }
    @Test void retargetingOverridesCleanFlagAndDoesNotUseOldSourceSamples() {
        var before=live();
        var retargeted=new HashMap<Pos,Pos>();aperture().forEach((p,s)->retargeted.put(p,s.add(100,0,0)));
        var after=update(p->Sample.UNKNOWN,p->Sample.UNKNOWN,retargeted,new Pos(1,0,0),before.snapshot(),false);
        assertFalse(after.field().available());assertNull(after.snapshot());
        var moved=new HashMap<Pos,Pos>();aperture().forEach((p,s)->moved.put(p.add(1,0,0),s));
        var move=update(p->Sample.UNKNOWN,this::source,moved,new Pos(1,0,0),before.snapshot(),false);
        assertFalse(move.field().available());assertNull(move.snapshot());
    }
}
