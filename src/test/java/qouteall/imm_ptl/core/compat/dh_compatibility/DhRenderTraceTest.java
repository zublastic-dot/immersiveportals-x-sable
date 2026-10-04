package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhRenderTraceTest {
    @Test void defaultOffDeadlineAndStopDoNotGrantExpensiveSamples() {
        var window = new DhRenderTrace.Window();
        assertFalse(window.reserve("view", 10));
        window.arm(2, 10);
        assertTrue(window.reserve("view", 10));
        assertFalse(window.reserve("view", 10 + DhRenderTrace.SAMPLE_NANOS - 1));
        assertTrue(window.reserve("view", 10 + DhRenderTrace.SAMPLE_NANOS));
        assertFalse(window.reserve("other", 2_000_000_010L));
        window.arm(2, 10); window.armed = false;
        assertFalse(window.reserve("view", 11));
        assertThrows(IllegalArgumentException.class, () -> window.arm(31, 10));
        assertThrows(IllegalArgumentException.class, () -> window.arm(0, 10));
    }

    @Test void arbitraryPortalViewsCannotGrowRetentionOrExtendCapture() {
        var window = new DhRenderTrace.Window();
        window.arm(30, 0);
        for (int i = 0; i < DhRenderTrace.MAX_KEYS; i++) {
            assertTrue(window.reserve("dimension" + i, 0));
            window.record("dimension" + i, "x".repeat(DhRenderTrace.MAX_TEXT + 1));
        }
        assertFalse(window.reserve("extra-dimension", DhRenderTrace.SAMPLE_NANOS));
        window.record("unreserved", "ignored");
        assertEquals(DhRenderTrace.MAX_KEYS, window.latest.size());
        assertEquals(DhRenderTrace.MAX_TEXT, window.latest.get("dimension0").length());
        for (int step = 1; step < 10; step++) {
            for (int i = 0; i < DhRenderTrace.MAX_KEYS; i++) window.reserve("dimension" + i, step * DhRenderTrace.SAMPLE_NANOS);
        }
        assertEquals(DhRenderTrace.MAX_SAMPLES, window.samples);
        assertFalse(window.active(5_000_000_000L));
        window.arm(1, 6_000_000_000L);
        assertTrue(window.latest.isEmpty());
        assertEquals(0, window.samples);
    }

    @Test void immediateRearmGetsANewCaptureIdentityAndClearsPriorBudget() {
        var window = new DhRenderTrace.Window();
        window.arm(2,0); long first=window.captureId;
        assertTrue(window.reserve("light-cache",0));
        window.armed=false;
        window.arm(2,1);
        assertNotEquals(first,window.captureId);
        assertTrue(window.reserve("light-cache",1));
        assertEquals(1,window.samples);
    }

    @Test void latestSampleReplacesOldTextWithoutRetainingWorldObjects() {
        var window = new DhRenderTrace.Window();
        window.arm(2, 0); window.reserve("mod:dimension/1", 0);
        window.record("mod:dimension/1", "first");
        window.record("mod:dimension/1", "second");
        assertEquals(1, window.latest.size());
        assertTrue(window.report(1).contains("second"));
        assertFalse(window.report(1).contains("first"));
    }
}
