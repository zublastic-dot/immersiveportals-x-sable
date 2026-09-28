package ipl.sable.transit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Stages all destination faces before retiring any source face. */
final class PortalTransferBatch<T> implements AutoCloseable {
    record Replacement<T>(T source, T destination) {}

    private final List<Replacement<T>> replacements;
    private final Consumer<T> discard;
    private boolean committed;

    private PortalTransferBatch(List<Replacement<T>> replacements, Consumer<T> discard) {
        this.replacements = List.copyOf(replacements);
        this.discard = discard;
    }

    /** Null means a destination rejected a face; the caller must not move its carrier. */
    static <T> PortalTransferBatch<T> prepare(List<T> sources, Function<T, T> copy,
                                             Predicate<T> publish, Consumer<T> discard) {
        List<Replacement<T>> staged = new ArrayList<>();
        PortalTransferBatch<T> batch;
        try {
            for (T source : sources) {
                staged.add(new Replacement<>(source, Objects.requireNonNull(copy.apply(source))));
            }
            batch = new PortalTransferBatch<>(staged, discard);
        } catch (RuntimeException failure) {
            cleanupAfterFailure(new PortalTransferBatch<>(staged, discard), failure);
            throw failure;
        }
        try {
            for (Replacement<T> pair : staged) {
                if (!publish.test(pair.destination())) {
                    batch.close();
                    return null;
                }
            }
            return batch;
        } catch (RuntimeException failure) {
            cleanupAfterFailure(batch, failure);
            throw failure;
        }
    }

    private static void cleanupAfterFailure(PortalTransferBatch<?> batch, RuntimeException failure) {
        try {
            batch.close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    List<Replacement<T>> replacements() { return replacements; }

    void commit(Consumer<T> retire) {
        if (committed) throw new IllegalStateException("Portal transfer already completed");
        // After retiring a source, its replacement must survive even if an entity
        // removal listener throws. Rolling it back then would destroy both copies.
        committed = true;
        for (Replacement<T> pair : replacements) retire.accept(pair.source());
    }

    @Override public void close() {
        if (committed) return;
        committed = true;
        RuntimeException failure = null;
        for (Replacement<T> pair : replacements) {
            try {
                discard.accept(pair.destination());
            } catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }
}
