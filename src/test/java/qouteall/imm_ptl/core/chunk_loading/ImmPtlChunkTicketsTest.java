package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ImmPtlChunkTicketsTest {
    final ImmPtlChunkTickets manager = new ImmPtlChunkTickets();
    final Backend backend = new Backend();

    void queue(int count) {
        for (long pos = 0; pos < count; pos++) manager.markForLoading(pos, (int) pos, 1);
    }

    void flush(long nanos, int radius) {
        manager.flushThrottling(backend, nanos, radius, true);
    }

    @Test void unpublishedHoldersRetainSlotsUntilRealCompletion() {
        queue(9);
        flush(0, 2);
        assertEquals(List.of(0L, 1L, 2L, 3L), backend.added);
        for (int i = 1; i < 20; i++) flush(i, 2);
        assertEquals(4, backend.added.size(), "Absent holders must not bypass throttling");
        backend.loaded(1);
        flush(20, 2);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), backend.added);
        assertTrue(manager.describe().contains("completed=1"));
    }

    @Test void c2meStyleUnloadedThenReplacedFutureEventuallyLoadsWithoutFlooding() {
        queue(8);
        flush(0, 2);
        for (long pos = 0; pos < 4; pos++) backend.futures.put(pos,
            CompletableFuture.completedFuture(backend.unloaded));
        for (int i = 1; i < 20; i++) flush(i, 2);
        assertEquals(4, backend.added.size());
        assertTrue(backend.failures.isEmpty());
        backend.futures.put(0L, new CompletableFuture<>());
        flush(21, 2);
        assertEquals(4, backend.added.size());
        backend.futures.get(0L).complete(ChunkResult.of("chunk"));
        flush(22, 2);
        assertEquals(5, backend.added.size());
        assertTrue(manager.describe().contains("waiting=4/4"));
    }

    @Test void requestedRadiusSurvivesASettingChangeDuringLoadingAndPurge() {
        queue(5);
        flush(0, 1);
        backend.loaded(0);
        flush(1, 2);
        assertEquals(1, backend.polledRadius.get(0L));
        assertEquals(2, backend.tickets.get(4L));
        manager.purge(backend, pos -> false);
        assertTrue(backend.tickets.isEmpty(), "Remove by the admitted radius, not the current setting");
        assertEquals(0, manager.getLoadedChunkNum());
    }

    @Test void removingADimensionReleasesAllAdmittedTicketsAndClearsPendingWork() {
        queue(10);
        flush(0, 2);
        manager.invalidate(backend);
        assertTrue(backend.tickets.isEmpty());
        assertEquals(4, backend.removed.size());
        assertEquals(0, manager.getLoadedChunkNum());
        flush(1, 2);
        assertEquals(4, backend.added.size(), "Invalidated dimensions cannot admit queued work");
        assertTrue(manager.describe().contains("queued=0 waiting=0/4"));
    }

    @Test void purgingAnInflightRequestFreesExactlyOneSlotAndDoesNotReportItsCancellation() {
        queue(6);
        flush(0, 2);
        manager.purge(backend, pos -> pos != 2);
        flush(1, 2);
        assertEquals(List.of(2L), backend.removed);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), backend.added);
        assertTrue(backend.failures.isEmpty());
    }

    @Test void genuineFailureIsReportedOnceWithItsReasonAndReleasesItsSlot() {
        queue(6);
        flush(0, 2);
        backend.futures.put(0L, CompletableFuture.completedFuture(ChunkResult.error("broken feature")));
        flush(1, 2);
        flush(2, 2);
        assertEquals(5, backend.added.size());
        assertEquals(1, backend.failures.size());
        assertEquals("broken feature", backend.failures.getFirst().error());
        assertTrue(manager.describe().contains("failed=1"));
    }

    @Test void persistentPendingRemainsObservableButDoesNotFloodTheLogOrAdmissionQueue() {
        queue(10);
        flush(0, 2);
        long interval = ImmPtlChunkTickets.STALL_REPORT_INTERVAL;
        flush(interval - 1, 2);
        assertEquals(0, backend.stalls);
        flush(interval, 2);
        assertEquals(4, backend.stalls);
        for (int i = 1; i < 20; i++) flush(interval + i, 2);
        assertEquals(4, backend.stalls);
        assertEquals(4, backend.added.size());
        flush(2 * interval, 2);
        assertEquals(8, backend.stalls);
    }

    @Test void disabledLoadingDoesNotConsumeThePendingQueueOrCreatePhantomWaiters() {
        queue(8);
        manager.flushThrottling(backend, 0, 2, false);
        assertTrue(backend.added.isEmpty());
        assertTrue(manager.describe().contains("queued=8 waiting=0/4"));
        flush(1, 2);
        assertEquals(4, backend.added.size());
    }

    @Test void nearerRequestsStillTakePriorityAfterAnUpdate() {
        manager.markForLoading(10L, 10, 1);
        manager.markForLoading(20L, 20, 1);
        manager.markForLoading(30L, 30, 1);
        manager.markForLoading(30L, 0, 2);
        flush(0, 2);
        assertEquals(List.of(30L, 10L, 20L), backend.added);
    }

    static class Backend implements ImmPtlChunkTickets.TicketAccess {
        final Map<Long, Integer> tickets = new HashMap<>();
        final Map<Long, Integer> polledRadius = new HashMap<>();
        final Map<Long, CompletableFuture<ChunkResult<String>>> futures = new HashMap<>();
        final List<Long> added = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final List<ChunkLoadObservation> failures = new ArrayList<>();
        final ChunkResult<String> unloaded = ChunkResult.error("Unloaded level chunk");
        int stalls;

        void loaded(long pos) {
            futures.put(pos, CompletableFuture.completedFuture(ChunkResult.of("chunk")));
        }

        public void add(long pos, int radius) {
            assertNull(tickets.putIfAbsent(pos, radius), "No duplicate admission");
            added.add(pos);
        }

        public void remove(long pos, int radius) {
            assertTrue(tickets.remove(pos, radius), "The exact admitted ticket must be removed");
            removed.add(pos);
        }

        public ChunkLoadObservation poll(long pos, int radius) {
            polledRadius.put(pos, radius);
            assertEquals(tickets.get(pos), radius, "Poll the stage actually requested by this ticket");
            var future = futures.get(pos);
            return future == null ? ChunkLoadObservation.WAITING : ChunkLoadObservation.poll(future, unloaded);
        }

        public void reportFailure(long pos, int radius, ChunkLoadObservation result) { failures.add(result); }
        public void reportStall(long pos, int radius, long seconds) { stalls++; }
    }
}
