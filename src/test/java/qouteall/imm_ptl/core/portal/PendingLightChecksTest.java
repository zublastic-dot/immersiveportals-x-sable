package qouteall.imm_ptl.core.portal;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PendingLightChecksTest {
    @Test void boundsWorkAndNeverRepeatsCompletedChecks() {
        var queue = new PendingLightChecks<>(List.of(1, 2, 3));
        var seen = new ArrayList<Integer>();
        queue.drain(2, p -> { seen.add(p); return true; });
        assertEquals(List.of(1, 2), seen);
        queue.drain(64, p -> { seen.add(p); return true; });
        queue.drain(64, p -> { fail("completed work repeated"); return true; });
        assertEquals(List.of(1, 2, 3), seen);
    }
    @Test void unloadedPositionsDoNotStarveLoadedOnesAndRetryLater() {
        var queue = new PendingLightChecks<>(List.of(1, 2, 3));
        var seen = new ArrayList<Integer>();
        queue.drain(2, p -> { seen.add(p); return p != 1; });
        queue.drain(2, p -> { seen.add(p); return p != 1; });
        assertEquals(List.of(1, 2, 3, 1), seen);
        queue.drain(64, p -> { seen.add(p); return true; });
        assertEquals(List.of(1, 2, 3, 1, 1), seen);
    }
    @Test void unavailablePositionIsCheckedOnlyOncePerTick() {
        var queue = new PendingLightChecks<>(List.of(1));
        var seen = new ArrayList<Integer>();
        queue.drain(64, p -> { seen.add(p); return false; });
        assertEquals(List.of(1), seen);
    }
    @Test void failedCheckRemainsPending() {
        var queue = new PendingLightChecks<>(List.of(1));
        assertThrows(IllegalStateException.class, () -> queue.drain(1, p -> { throw new IllegalStateException(); }));
        var seen = new ArrayList<Integer>();
        queue.drain(1, p -> { seen.add(p); return true; });
        assertEquals(List.of(1), seen);
    }
}
