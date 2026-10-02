package qouteall.imm_ptl.core.render.impostor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.render.impostor.PortalImpostorPolicy.*;

class PortalImpostorPolicyTest {
    @Test
    void cutoffLeavesEngineHeadroomAndHonorsConfiguredLimit() {
        assertEquals(128.0, liveCutoff(128.0, 256.0));
        assertEquals(108.8, liveCutoff(128.0, 128.0), 1e-10);
        assertEquals(32.0, liveCutoff(32.0, 128.0));
        assertEquals(8.0, liveCutoff(1.0, 128.0));
    }

    @Test
    void lowEngineRangeIsNeverRaisedAboveItsHardLimit() {
        for (double range : new double[] {0.01, 1.0, 4.0, 8.0, 9.0}) {
            double cutoff = liveCutoff(128.0, range);
            assertTrue(cutoff > 0.0);
            assertTrue(cutoff < range);
            assertEquals(range * 0.85, cutoff);
        }
    }

    @Test
    void invalidConfigurationUsesDefaultButUnknownEngineDisablesDistanceAdmission() {
        for (double configured : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(DEFAULT_LIVE_CUTOFF, liveCutoff(configured, 256.0));
        }
        for (double range : new double[] {0.0, -1.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double cutoff = liveCutoff(128.0, range);
            assertEquals(0.0, cutoff);
            assertFalse(useCached(true, 200.0, cutoff));
        }
    }

    @Test
    void thresholdJitterDoesNotRepeatedlySwitchRenderingModes() {
        boolean far = false;
        for (double distance : new double[] {127.0, 128.0, 127.99}) {
            far = useCached(far, distance, 128.0);
            assertFalse(far);
        }
        far = useCached(far, 128.01, 128.0);
        assertTrue(far);
        for (double distance : new double[] {128.0, 127.9, 128.1, 124.0, 120.01}) {
            far = useCached(far, distance, 128.0);
            assertTrue(far);
        }
        assertFalse(useCached(far, 120.0, 128.0));
    }

    @Test
    void shortRangeHysteresisNeverConsumesMoreThanQuarterOfCutoff() {
        assertTrue(useCached(true, 6.01, 8.0));
        assertFalse(useCached(true, 6.0, 8.0));
        assertFalse(useCached(false, 8.0, 8.0));
        assertTrue(useCached(false, 8.01, 8.0));
    }

    @Test
    void invalidDistanceOrCutoffCannotSelectCache() {
        for (double distance : new double[] {-1.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(useCached(false, distance, 128.0));
            assertFalse(useCached(true, distance, 128.0));
        }
        for (double cutoff : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(useCached(false, 200.0, cutoff));
            assertFalse(useCached(true, 200.0, cutoff));
        }
    }

    @Test
    void resolutionCannotExceedGpuBounds() {
        assertEquals(256, resolution(0));
        assertEquals(256, resolution(Integer.MIN_VALUE));
        assertEquals(64, resolution(1));
        assertEquals(64, resolution(64));
        assertEquals(320, resolution(320));
        assertEquals(512, resolution(512));
        assertEquals(512, resolution(Integer.MAX_VALUE));
    }

    @Test
    void farPortalWithoutImageStillRequestsLiveRendering() {
        State state = new State();
        assertLive(state.decide(200.0, 128.0, false, 0));
        assertFalse(state.isFar());
        state.liveRendered(1);
        assertLive(state.decide(200.0, 128.0, false, 2));
        assertCached(state.decide(200.0, 128.0, true, 3));
        assertTrue(state.isFar());
    }

    @Test
    void noTimeoutCanSubstituteForFreshLiveRenderOnReentry() {
        State state = new State();
        assertCached(state.decide(200, 128, true, 0));
        assertWaiting(state.decide(119, 128, true, 1));
        assertFalse(state.isFar());
        assertWaiting(state.decide(50, 128, true, 600_000_000_000L));
        assertWaiting(state.decide(1, 128, true, 3_600_000_000_000L));
    }

    @Test
    void reentryFadesOldImageOnlyAfterCompletedLiveRender() {
        State state = new State();
        state.decide(200, 128, true, 0);
        assertWaiting(state.decide(119, 128, true, 10));
        long rendered = 1_000_000_000L;
        state.liveRendered(rendered);
        assertWaiting(state.decide(119, 128, true, rendered));
        Decision quarter = state.decide(119, 128, true, rendered + 50_000_000L);
        assertTrue(quarter.renderLive());
        assertTrue(quarter.drawCached());
        assertEquals(0.84375f, quarter.cachedAlpha());
        assertEquals(0.5f, state.decide(119, 128, true, rendered + 100_000_000L).cachedAlpha());
        assertLive(state.decide(119, 128, true, rendered + REENTRY_BLEND_NANOS));
    }

    @Test
    void subsequentLiveRendersDoNotRestartReentryBlend() {
        State state = new State();
        state.decide(200, 128, true, 0);
        state.decide(119, 128, true, 1);
        state.liveRendered(10);
        state.liveRendered(100_000_010L);
        assertLive(state.decide(119, 128, true, 200_000_010L));
    }

    @Test
    void returningFarDuringBlendCancelsItAndRequiresAnotherLiveRenderNextTime() {
        State state = new State();
        state.decide(200, 128, true, 0);
        state.decide(119, 128, true, 1);
        state.liveRendered(2);
        assertCached(state.decide(140, 128, true, 100_000_000L));
        assertWaiting(state.decide(119, 128, true, 400_000_000L));
        assertWaiting(state.decide(119, 128, true, 4_000_000_000L));
    }

    @Test
    void invalidatedImageCannotRemainAsAReentryOverlay() {
        State state = new State();
        state.decide(200, 128, true, 0);
        state.decide(119, 128, true, 1);
        assertLive(state.decide(119, 128, false, 2));
        state.liveRendered(3);
        assertLive(state.decide(119, 128, true, 4));
    }

    @Test
    void invalidatedFarImageRequestsLiveRatherThanDrawingStaleContents() {
        State state = new State();
        assertCached(state.decide(200, 128, true, 0));
        assertLive(state.decide(200, 128, false, 1));
        assertFalse(state.isFar());
    }

    @Test
    void unknownDistanceOrEngineGateDropsEvenAPreviouslyCachedOverlay() {
        State state = new State();
        state.decide(200, 128, true, 0);
        assertLive(state.decide(200, 0, true, 1));
        assertFalse(state.isFar());
        state.decide(200, 128, true, 2);
        assertLive(state.decide(Double.NaN, 128, true, 3));
    }

    @Test
    void resetAndUnrelatedLiveRenderDoNotStartAnOverlay() {
        State state = new State();
        state.liveRendered(0);
        assertLive(state.decide(50, 128, true, 1));
        state.decide(200, 128, true, 2);
        state.reset();
        assertFalse(state.isFar());
        assertLive(state.decide(125, 128, true, 3));
    }

    @Test
    void backwardClockCannotDiscardOldImageEarly() {
        State state = new State();
        state.decide(200, 128, true, 0);
        state.decide(119, 128, true, 1);
        state.liveRendered(1_000_000_000L);
        assertWaiting(state.decide(119, 128, true, 500_000_000L));
    }

    @Test
    void monotonicClockWrapKeepsTheCorrectBlendInterval() {
        State state = new State();
        state.decide(200, 128, true, 0);
        state.decide(119, 128, true, 1);
        long started = Long.MAX_VALUE - 100_000_000L;
        state.liveRendered(started);
        assertEquals(0.5f, state.decide(119, 128, true, started + 100_000_000L).cachedAlpha());
        assertLive(state.decide(119, 128, true, started + 200_000_000L));
    }

    private static void assertLive(Decision decision) {
        assertTrue(decision.renderLive());
        assertFalse(decision.drawCached());
        assertEquals(0.0f, decision.cachedAlpha());
    }

    private static void assertCached(Decision decision) {
        assertFalse(decision.renderLive());
        assertTrue(decision.drawCached());
        assertEquals(1.0f, decision.cachedAlpha());
    }

    private static void assertWaiting(Decision decision) {
        assertTrue(decision.renderLive());
        assertTrue(decision.drawCached());
        assertEquals(1.0f, decision.cachedAlpha());
    }
}
