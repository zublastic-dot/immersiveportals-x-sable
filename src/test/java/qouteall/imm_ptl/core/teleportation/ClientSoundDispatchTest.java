package qouteall.imm_ptl.core.teleportation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClientSoundDispatchTest {
    @Test void animationWorkerNeverTouchesWorldOrPlaysSound() throws Exception {
        Thread client = Thread.currentThread();
        var pending = new LinkedBlockingQueue<Runnable>();
        var calls = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            assertTrue(workers.submit(() -> ClientSoundDispatch.defer(false, pending::add, () -> {
                assertSame(client, Thread.currentThread());
                return true;
            }, () -> {
                assertSame(client, Thread.currentThread());
                calls.incrementAndGet();
            })).get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, calls.get());
        assertEquals(1, pending.size());
        pending.remove().run();
        assertEquals(1, calls.get());
        assertTrue(pending.isEmpty());
    }

    @Test void unloadingOrReplacingWorldBeforePlaybackDiscardsStaleSound() {
        var pending = new LinkedBlockingQueue<Runnable>();
        Object oldWorld = new Object();
        var loaded = new AtomicReference<>(oldWorld);
        assertTrue(ClientSoundDispatch.defer(false, pending::add,
            () -> loaded.get() == oldWorld, () -> fail("stale world sound")));
        loaded.set(new Object());
        pending.remove().run();
        assertTrue(ClientSoundDispatch.defer(false, pending::add,
            () -> loaded.get() == oldWorld, () -> fail("disconnected sound")));
        loaded.set(null);
        pending.remove().run();
    }

    @Test void clientThreadUsesOriginalSynchronousPlayback() {
        assertFalse(ClientSoundDispatch.defer(true, task -> fail("unnecessary queue"),
            () -> { fail("not a deferred call"); return false; },
            () -> fail("original handler must play, not dispatch")));
    }
}
