package qouteall.imm_ptl.core.render.impostor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalImpostorRateTest {
    @Test void productionRateLimitHasTheSameWindowForNegativeZeroAndPositiveClockOrigins() {
        for (long start : new long[]{-1_000_000, 0, 1_000_000}) {
            var rate = new PortalImpostorSync.Rate();
            for (int i = 0; i < 64; i++) assertTrue(rate.allow(start + i));
            assertFalse(rate.allow(start + 999));
            assertTrue(rate.allow(start + 1000));
            for (int i = 1; i < 64; i++) assertTrue(rate.allow(start + 1000));
            assertFalse(rate.allow(start + 1000));
        }
    }

    @Test void rejectedRequestsDoNotMoveTheNextWindowOrExpandTheCounter() {
        var rate = new PortalImpostorSync.Rate();
        for (int i = 0; i < 64; i++) assertTrue(rate.allow(-5000));
        for (int i = 0; i < 1000; i++) assertFalse(rate.allow(-4001));
        assertEquals(64, rate.count);
        assertTrue(rate.allow(-4000));
    }
}
