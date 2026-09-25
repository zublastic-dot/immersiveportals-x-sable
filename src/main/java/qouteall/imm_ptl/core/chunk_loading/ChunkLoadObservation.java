package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A nonblocking observation, not a cached future: holders can replace futures. */
record ChunkLoadObservation(State state, String error, Throwable cause) {
    enum State { WAITING, LOADED, FAILED }

    static final ChunkLoadObservation WAITING = new ChunkLoadObservation(State.WAITING, null, null);
    static final ChunkLoadObservation LOADED = new ChunkLoadObservation(State.LOADED, null, null);

    static <T> ChunkLoadObservation poll(
        CompletableFuture<ChunkResult<T>> future, ChunkResult<T> unloaded
    ) {
        try {
            ChunkResult<T> result = future.getNow(null);
            // Vanilla and C2ME use this sentinel before promotion, not just on failure.
            // Do not match its text: a different failure must retain its explanation.
            if (result == null || result == unloaded) {
                return WAITING;
            }
            return result.isSuccess() ? LOADED :
                new ChunkLoadObservation(State.FAILED, result.getError(), null);
        }
        catch (CompletionException | CancellationException exception) {
            Throwable cause = exception;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return new ChunkLoadObservation(State.FAILED, cause.toString(), cause);
        }
    }
}
