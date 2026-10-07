package ipl.sable.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class IgnitionTraceWindowTest {
    private final UUID player = UUID.randomUUID(), token = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong();
    private IgnitionTraceWindow window(int attempts, int seconds) {
        return new IgnitionTraceWindow(token, player, "test:parent", 20481033, 208, 20509704,
            2, attempts, seconds, clock::get);
    }
    @Test void unrelatedPlayersDimensionsAndHitsCannotConsumeTheCaptureBudget() {
        var w = window(1, 60);
        assertNull(w.begin(UUID.randomUUID(), "test:parent", w.x, w.y, w.z));
        assertNull(w.begin(player, "test:other", w.x, w.y, w.z));
        assertNull(w.begin(player, "test:parent", w.x + 3, w.y, w.z));
        assertNull(w.begin(player, "test:parent", Integer.MIN_VALUE, w.y, w.z));
        assertNotNull(w.begin(player, "test:parent", w.x + 2, w.y - 2, w.z));
        assertNull(w.begin(player, "test:parent", w.x, w.y, w.z));
    }
    @Test void lastAdmittedAttemptCanFinishButCannotExceedItsEventLimit() {
        var w = window(1, 60);
        var a = w.begin(player, "test:parent", w.x, w.y, w.z);
        assertNotNull(a);
        assertNull(w.begin(player, "test:parent", w.x, w.y, w.z));
        for (int i = 0; i < IgnitionTraceWindow.MAX_EVENTS; i++) assertTrue(a.event());
        assertFalse(a.event());
    }
    @Test void expiryStopsBothNewAttemptsAndAnOpenAttempt() {
        var w = window(8, 2);
        var a = w.begin(player, "test:parent", w.x, w.y, w.z);
        clock.set(1_999_999_999L); assertTrue(a.event());
        clock.incrementAndGet();
        assertFalse(a.event()); assertFalse(w.alive());
        assertNull(w.begin(player, "test:parent", w.x, w.y, w.z));
    }
    @Test void explicitStopAndAttemptCloseAreTerminal() {
        var w = window(8, 60); var a = w.begin(player, "test:parent", w.x, w.y, w.z);
        a.close(); assertFalse(a.event());
        var b = w.begin(player, "test:parent", w.x, w.y, w.z);
        w.stop(); assertFalse(b.event());
        assertNull(w.begin(player, "test:parent", w.x, w.y, w.z));
    }
    @Test void commandBoundsAlsoApplyToRemoteArming() {
        for (int attempts : new int[]{0, 9}) assertThrows(IllegalArgumentException.class, () -> window(attempts, 60));
        for (int seconds : new int[]{0, 121}) assertThrows(IllegalArgumentException.class, () -> window(1, seconds));
        assertThrows(IllegalArgumentException.class, () -> new IgnitionTraceWindow(token, player,
            "test:parent", 0, 0, 0, 33, 1, 60, clock::get));
    }
}
