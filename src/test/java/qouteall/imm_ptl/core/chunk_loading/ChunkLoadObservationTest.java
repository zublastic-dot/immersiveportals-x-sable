package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadObservationTest {
    private final ChunkResult<String> unloaded = ChunkResult.error("Unloaded level chunk");

    @Test void pendingFutureDoesNotBlockOrCompleteTheRequest() {
        CompletableFuture<ChunkResult<String>> future = new CompletableFuture<>();
        assertSame(ChunkLoadObservation.WAITING, ChunkLoadObservation.poll(future, unloaded));
        future.complete(ChunkResult.of("real chunk"));
        assertSame(ChunkLoadObservation.LOADED, ChunkLoadObservation.poll(future, unloaded));
    }

    @Test void unloadedSentinelIsNotACompletedGenerationFailure() {
        assertSame(ChunkLoadObservation.WAITING,
            ChunkLoadObservation.poll(CompletableFuture.completedFuture(unloaded), unloaded));
    }

    @Test void unrelatedFailedResultsAreNotHiddenEvenIfTheirTextMatches() {
        var result = ChunkLoadObservation.poll(
            CompletableFuture.completedFuture(ChunkResult.<String>error("Unloaded level chunk")), unloaded);
        assertEquals(ChunkLoadObservation.State.FAILED, result.state());
        assertEquals("Unloaded level chunk", result.error());
    }

    @Test void exceptionalFuturePreservesTheRootCause() {
        var cause = new IllegalStateException("generation failure with coordinates");
        var result = ChunkLoadObservation.poll(
            CompletableFuture.<ChunkResult<String>>failedFuture(new CompletionException(cause)), unloaded);
        assertEquals(ChunkLoadObservation.State.FAILED, result.state());
        assertSame(cause, result.cause());
        assertTrue(result.error().contains(cause.getMessage()));
    }

    @Test void unexpectedCancellationIsReportedRatherThanThrownFromTheServerTick() {
        CompletableFuture<ChunkResult<String>> future = new CompletableFuture<>();
        future.cancel(false);
        var result = ChunkLoadObservation.poll(future, unloaded);
        assertEquals(ChunkLoadObservation.State.FAILED, result.state());
        assertInstanceOf(java.util.concurrent.CancellationException.class, result.cause());
    }
}
