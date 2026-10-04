package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalColoredLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

class PortalColoredLightFieldTest {
    private static final Pos ORIGIN = new Pos(0, 0, 0);
    private static Cell emitter(int red, int green, int blue) { return new Cell(new Rgb(red, green, blue), Rgb.WHITE, 15); }
    private static Job finish(Job job) {
        for (int attempts = 0; !job.complete() && attempts < 1000; attempts++) job.advance(4096, 8192);
        assertTrue(job.complete()); return job;
    }
    @Test void nativeEmissionEscapesOpaqueSourceAndComponentsMergeWithoutAddingBrightness() {
        var red = ORIGIN.add(-2, 0, 0); var blue = ORIGIN.add(0, 0, 3);
        var job = finish(new Job(p -> p.equals(red) ? emitter(15, 3, 0) : p.equals(blue) ? emitter(0, 4, 15) : Cell.AIR, List.of(ORIGIN)));
        assertEquals(new Rgb(13, 1, 12), job.at(ORIGIN));
        assertEquals(new Average(13, 1, 12), job.average());
    }
    @Test void targetOpacityAndFilterCapUseNativePerChannelRules() {
        Cell target = new Cell(Rgb.DARK, new Rgb(3, 15, 5), 4);
        var job = finish(new Job(p -> p.equals(ORIGIN) ? target : p.equals(ORIGIN.add(1, 0, 0)) ? emitter(15, 8, 11) : Cell.UNKNOWN, List.of(ORIGIN)));
        assertEquals(new Rgb(3, 4, 5), job.at(ORIGIN));
    }
    @Test void unloadedCellsAreOpaqueAndCannotBeAssumedToBeAir() {
        var source = ORIGIN.add(2, 0, 0);
        var job = finish(new Job(p -> p.equals(source) ? emitter(15, 0, 0) : p.equals(ORIGIN) ? Cell.AIR : Cell.UNKNOWN, List.of(ORIGIN)));
        assertEquals(Rgb.DARK, job.at(ORIGIN));
    }
    @Test void fourteenBlockSupportIncludesLastPositiveLightButNeverInventsLongRangeLight() {
        var job = finish(new Job(p -> p.equals(ORIGIN.add(14, 0, 0)) ? emitter(15, 0, 0) : Cell.AIR, List.of(ORIGIN)));
        assertEquals(new Rgb(1, 0, 0), job.at(ORIGIN));
        var outside = finish(new Job(p -> p.equals(ORIGIN.add(15, 0, 0)) ? emitter(15, 0, 0) : Cell.AIR, List.of(ORIGIN)));
        assertEquals(Rgb.DARK, outside.at(ORIGIN));
    }
    @Test void apertureMeanPreservesMagnitudeIncludesDarkCellsAndDoesNotWeightBrightnessTwice() {
        var job = finish(new Job(p -> p.equals(ORIGIN) ? emitter(15, 4, 0) : Cell.AIR, List.of(ORIGIN, ORIGIN.add(15, 0, 0))));
        assertEquals(new Average(7.5f, 2, 0), job.average());
    }
    @Test void incrementalCaptureHasStrictBudgetsAndNeverPublishesHalfCapturedColor() {
        var reads = new AtomicInteger();
        var job = new Job(p -> { reads.incrementAndGet(); return Cell.AIR; }, List.of(ORIGIN));
        assertThrows(IllegalStateException.class, job::average);
        Work first = job.advance(7, 3);
        assertEquals(7, reads.get()); assertEquals(7, first.reads()); assertEquals(0, first.steps()); assertFalse(first.complete());
        finish(job); assertEquals(job.volume(), reads.get()); assertEquals(new Average(0, 0, 0), job.average());
        assertEquals(new Work(0, 0, true), job.advance(1000, 1000));
    }
    @Test void boundsRejectExtremeCoordinatesAndUnboundedVolumeBeforeReading() {
        Reader forbidden = p -> { fail("oversized requests may not read the world"); return Cell.AIR; };
        assertThrows(IllegalArgumentException.class, () -> new Job(forbidden, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Job(forbidden, List.of(new Pos(Integer.MAX_VALUE, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Job(forbidden, List.of(ORIGIN, new Pos(80, 80, 80))));
    }
    @Test void closedLoopsCannotAmplifyOrPreserveRemovedNativeSources() {
        var lit = finish(new Job(p -> p.equals(ORIGIN) ? emitter(7, 2, 5) : Cell.AIR, List.of(ORIGIN.add(1, 0, 0))));
        assertEquals(new Rgb(6, 1, 4), lit.at(ORIGIN.add(1, 0, 0)));
        var removed = finish(new Job(p -> Cell.AIR, List.of(ORIGIN.add(1, 0, 0))));
        assertEquals(Rgb.DARK, removed.at(ORIGIN.add(1, 0, 0)));
    }
}
