package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalColoredLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

class PortalNativeColorCacheTest {
    private static final Pos TORCH=new Pos(8,8,8);
    private static Cell emitter(Rgb color) { return new Cell(color,Rgb.WHITE,0); }
    private static <W> void finish(PortalNativeColorCache<W> cache,long from,long until) {
        for (long tick=from;tick<until;tick++) cache.advance(tick,k -> k.x*k.x+k.y*k.y+k.z*k.z);
    }
    @Test void remoteSameNameWorldsHaveIndependentNativeColorsAndNoExperimentalFlag() {
        record World(String dimension) {}
        World red=new World("other:anywhere"),blue=new World("other:anywhere");
        assertEquals(red,blue);
        var cache=new PortalNativeColorCache<World>(world -> p -> p.equals(TORCH)
            ? emitter(world==red ? new Rgb(15,0,0) : new Rgb(0,0,15)) : Cell.AIR,() -> 0);
        cache.worlds(List.of(red,blue));
        assertNull(cache.sample(red,8.5,8.5,8.5)); assertNull(cache.sample(blue,8.5,8.5,8.5));
        finish(cache,0,40);
        assertEquals(0xff0000,cache.sample(red,8.5,8.5,8.5));
        assertEquals(0x0000ff,cache.sample(blue,8.5,8.5,8.5));
        assertEquals(0x0000ee,cache.sample(blue,9.5,8.5,8.5));
    }
    @Test void workerRequestsNeverReadWorldsAndDoNotExposeIncompleteDarkCapture() throws Exception {
        Object world=new Object(); Thread client=Thread.currentThread(); var calls=new AtomicInteger();
        var cache=new PortalNativeColorCache<Object>(w -> p -> {
            assertSame(client,Thread.currentThread()); calls.incrementAndGet();
            return p.equals(TORCH) ? emitter(new Rgb(0,0,15)) : Cell.AIR;
        },() -> 0);
        cache.worlds(List.of(world));
        var request=new FutureTask<Void>(() -> { assertNull(cache.sample(world,8.5,8.5,8.5)); return null; });
        Thread worker=new Thread(request);
        worker.start(); request.get(); assertEquals(0,calls.get());
        cache.advance(0,k -> 0); assertTrue(calls.get()>0);
        assertNull(cache.sample(world,8.5,8.5,8.5));
        finish(cache,1,25);
        var query=new FutureTask<Integer>(() -> cache.sample(world,8.5,8.5,8.5));
        worker=new Thread(query);
        worker.start(); assertEquals(0xff,query.get());
    }
    @Test void fullPropagationHaloAndCellCenteredInterpolationMatchAtSectionBoundary() {
        Object world=new Object(); Pos source=new Pos(29,8,8);
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(source)
            ? emitter(new Rgb(15,0,0)) : Cell.AIR,() -> 0);
        cache.worlds(List.of(world));
        cache.sample(world,15.5,8.5,8.5); cache.sample(world,16.5,8.5,8.5);
        finish(cache,0,45);
        assertEquals(17<<16,cache.sample(world,15.5,8.5,8.5));
        assertEquals(34<<16,cache.sample(world,16.5,8.5,8.5));
        assertEquals(26<<16,cache.sample(world,16,8.5,8.5));
        assertTrue(Math.abs((cache.sample(world,15.999999,8.5,8.5)>>>16)
            -(cache.sample(world,16.000001,8.5,8.5)>>>16))<=1);
    }
    @Test void sameOpacitySourceReplacementAndRemovalRefreshWithoutLosingLastCompletePublication() {
        Object world=new Object(); var color=new AtomicReference<>(new Rgb(0,0,15));
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(TORCH)
            ? emitter(color.get()) : Cell.AIR,() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,8.5,8.5,8.5); finish(cache,0,25);
        assertEquals(0xff,cache.sample(world,8.5,8.5,8.5));
        color.set(new Rgb(15,0,0)); cache.invalidate(world,TORCH);
        assertEquals(0xff,cache.sample(world,8.5,8.5,8.5));
        finish(cache,25,30); color.set(Rgb.DARK); cache.invalidate(world,TORCH);
        finish(cache,30,60); assertEquals(0,cache.sample(world,8.5,8.5,8.5));
        assertTrue(cache.completed>=2,"Changing a capture's input must schedule a follow-up capture");
    }
    @Test void opaqueAndColoredFiltersUseDestinationGeometry() {
        Object world=new Object();
        var cache=new PortalNativeColorCache<Object>(w -> p -> {
            if (p.equals(TORCH)) return emitter(new Rgb(15,0,15));
            if (p.equals(TORCH.add(1,0,0))) return new Cell(Rgb.DARK,new Rgb(0,0,15),0);
            return Cell.UNKNOWN;
        },() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,8.5,8.5,8.5); finish(cache,0,20);
        assertEquals(0x0000ee,cache.sample(world,9.5,8.5,8.5));
        assertEquals(0,cache.sample(world,10.5,8.5,8.5));
    }
    @Test void replacementsAndPrimaryWorldTransitionRevokeOldPublicationsAndDeadDirtyReferences() {
        record World(String dimension) {}
        World old=new World("modded:moon"),replacement=new World("modded:moon");
        var cache=new PortalNativeColorCache<World>(w -> p -> p.equals(TORCH)
            ? emitter(new Rgb(0,15,0)) : Cell.AIR,() -> 0);
        cache.worlds(List.of(old)); cache.sample(old,8.5,8.5,8.5); finish(cache,0,25);
        cache.worlds(List.of(replacement));
        assertFalse(cache.accepts(old)); assertNull(cache.sample(old,8.5,8.5,8.5));
        assertNull(cache.sample(replacement,8.5,8.5,8.5)); assertEquals(0,cache.ready());
        cache.discardUnloadedWorlds(List.of(replacement)); assertEquals(0,cache.pendingRebuilds());
        finish(cache,25,50); assertEquals(0xff00,cache.sample(replacement,8.5,8.5,8.5));
        cache.worlds(List.of()); // That same level became the primary/player world.
        assertFalse(cache.accepts(replacement)); assertNull(cache.sample(replacement,8.5,8.5,8.5));
        assertTrue(cache.pendingRebuilds()>0,"Existing colored meshes are handed back to the native engine");
        cache.clear(); assertEquals(0,cache.pendingRebuilds()); assertEquals(0,cache.size());
    }
    @Test void unreadyInitialMeshRebuildIsRetriedAfterAtomicPublication() {
        Object world=new Object();
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(TORCH)
            ? emitter(new Rgb(0,0,15)) : Cell.AIR,() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,8.5,8.5,8.5); finish(cache,0,25);
        int queued=cache.pendingRebuilds(); assertEquals(27,queued);
        for (int i=0;i<8;i++) cache.rebuild(section -> {
            assertEquals(0xff,cache.sample(world,8.5,8.5,8.5)); return false;
        });
        assertEquals(queued,cache.pendingRebuilds());
        for (int i=0;i<4;i++) cache.rebuild(section -> true);
        assertEquals(0,cache.pendingRebuilds());
    }
    @Test void saturationRetainsNearFieldsInsteadOfEvictRebuildRequestChurn() {
        Object world=new Object(); var cache=new PortalNativeColorCache<Object>(w -> p -> Cell.UNKNOWN,() -> 0);
        cache.worlds(List.of(world));
        for (int x=0;x<512;x++) cache.sample(world,x*16+.5,.5,.5);
        assertEquals(PortalNativeColorCache.MAX_FIELDS,cache.pending());
        cache.advance(0,k -> k.x); assertEquals(128,cache.size());
        for (int tick=1;tick<4;tick++) {
            for (int x=128;x<256;x++) cache.sample(world,x*16+.5,.5,.5);
            cache.advance(tick,k -> k.x);
        }
        assertEquals(128,cache.size()); assertEquals(0,cache.pendingRebuilds());
        assertTrue(cache.describe(w -> "world").stream().allMatch(item -> ((List<?>)item.get("section")).getFirst().equals(0)
            || (Integer)((List<?>)item.get("section")).getFirst()<128));
    }
    @Test void cooperativeDeadlineAndSingleTickBudgetApplyAcrossAllRequests() {
        Object world=new Object(); var clock=new AtomicLong(); var calls=new AtomicInteger();
        var cache=new PortalNativeColorCache<Object>(w -> p -> {
            calls.incrementAndGet(); clock.addAndGet(500_000); return Cell.AIR;
        },clock::get);
        cache.worlds(List.of(world)); cache.sample(world,.5,.5,.5); cache.sample(world,16.5,.5,.5);
        cache.advance(0,k -> k.x); assertEquals(4,calls.get()); assertEquals(4,cache.reads);
        cache.advance(0,k -> k.x); assertEquals(4,calls.get());
        cache.advance(1,k -> k.x); assertEquals(8,calls.get());
        assertEquals(2_000_000,cache.elapsed); assertEquals(0,cache.ready());
    }
    @Test void apertureSeedRescuesNearSectionAfterDistantWorkersFillRequestQueue() {
        Object world=new Object(); var cache=new PortalNativeColorCache<Object>(w -> p -> Cell.UNKNOWN,() -> 0);
        cache.worlds(List.of(world));
        for (int x=128;x<256;x++) cache.sample(world,x*16+.5,.5,.5);
        cache.seed(new PortalNativeColorCache.Section<>(world,0,0,0),k -> k.x);
        cache.advance(0,k -> k.x);
        assertEquals(128,cache.size());
        assertTrue(cache.describe(w -> "world").stream().anyMatch(item -> item.get("section").equals(List.of(0,0,0))));
        finish(cache,1,15);
        assertEquals(0,cache.sample(world,.5,.5,.5));
    }
    @Test void configReloadInvalidatesWithoutAnyGeometryChangeOrWorldSwitch() {
        Object world=new Object(); var emission=new AtomicReference<>(new Rgb(15,0,0));
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(TORCH)
            ? emitter(emission.get()) : Cell.AIR,() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,8.5,8.5,8.5); finish(cache,0,25);
        assertEquals(0xff0000,cache.sample(world,8.5,8.5,8.5));
        emission.set(new Rgb(0,0,15)); cache.invalidateAll();
        assertEquals(0xff0000,cache.sample(world,8.5,8.5,8.5)); finish(cache,25,50);
        assertEquals(0xff,cache.sample(world,8.5,8.5,8.5));
    }
    @Test void unrelatedContinuousInvalidationsNeverEraseBlueOrRestartCaptureForever() {
        Object world=new Object(); var color=new AtomicReference<>(new Rgb(0,0,15));
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(TORCH) ? emitter(color.get()) : Cell.AIR,() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,8.5,8.5,8.5); finish(cache,0,25);
        for (int tick=25;tick<70;tick++) {
            cache.invalidate(world,TORCH.add(12,0,0)); cache.advance(tick,k -> 0);
            assertEquals(0xff,cache.sample(world,8.5,8.5,8.5),"A pending refresh must never expose vanilla fallback");
        }
        assertTrue(cache.completed>2,"Frequent unrelated updates cannot cancel every capture");
        color.set(new Rgb(15,0,0));
        for (int tick=70;tick<115;tick++) {
            cache.invalidate(world,TORCH); cache.advance(tick,k -> 0);
        }
        assertEquals(0xff0000,cache.sample(world,8.5,8.5,8.5),"Real source changes still replace the retained field");
    }
    @Test void crossingBackReusesStillLoadedWorldSnapshotWithoutASecondWarmup() {
        Object nether=new Object(),other=new Object();
        var cache=new PortalNativeColorCache<Object>(w -> p -> p.equals(TORCH) ? emitter(new Rgb(0,0,15)) : Cell.AIR,() -> 0);
        cache.worlds(List.of(nether),List.of(nether,other));
        cache.sample(nether,8.5,8.5,8.5); finish(cache,0,25);
        long completed=cache.completed;
        cache.worlds(List.of(other),List.of(nether,other));
        assertFalse(cache.accepts(nether)); assertNull(cache.sample(nether,8.5,8.5,8.5));
        assertEquals(0xff,cache.sampleRetained(nether,8.5,8.5,8.5),"Primary warmup may read an existing snapshot");
        int pending=cache.pending();
        assertNull(cache.sampleRetained(nether,1000,8.5,8.5));
        assertEquals(pending,cache.pending(),"Warmup never creates primary-world snapshot work");
        assertEquals(1,cache.ready(),"Dormant snapshots retain their exact ClientLevel identity");
        cache.worlds(List.of(nether),List.of(nether,other));
        assertEquals(0xff,cache.sample(nether,8.5,8.5,8.5)); assertEquals(completed,cache.completed);
    }
    @Test void changingNearFieldCannotStarveInitialCaptureOfNeighboringField() {
        Object world=new Object(); var cache=new PortalNativeColorCache<Object>(w -> p -> Cell.AIR,() -> 0);
        cache.worlds(List.of(world)); cache.sample(world,.5,.5,.5); finish(cache,0,15);
        cache.sample(world,16.5,.5,.5);
        for (int tick=15;tick<65;tick++) {
            cache.invalidate(world,new Pos(-15,0,0)); cache.advance(tick,k -> k.x);
        }
        assertEquals(0,cache.sample(world,16.5,.5,.5)); assertEquals(2,cache.ready());
    }
    @Test void invalidAndPrimaryRequestsCannotReadOrAllocateAnyField() {
        Object remote=new Object(),primary=new Object(); var calls=new AtomicInteger();
        var cache=new PortalNativeColorCache<Object>(w -> { calls.incrementAndGet(); return null; },() -> 0);
        cache.worlds(List.of(remote));
        assertNull(cache.sample(primary,0,0,0)); assertNull(cache.sample(remote,Double.NaN,0,0));
        assertNull(cache.sample(remote,Double.POSITIVE_INFINITY,0,0)); assertNull(cache.sample(remote,40_000_000,0,0));
        cache.advance(0,k -> 0); assertEquals(0,calls.get()); assertEquals(0,cache.size());
        cache.sample(remote,.5,.5,.5); cache.advance(1,k -> 0);
        assertEquals(1,calls.get()); assertEquals(0,cache.ready());
    }
}
