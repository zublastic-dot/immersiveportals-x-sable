package qouteall.imm_ptl.core.portal;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.function.Predicate;

/** One-shot lighting checks, deferring unloaded positions without starving loaded ones. */
public final class PendingLightChecks<T> {
    private final ArrayDeque<T> pending;

    public PendingLightChecks(Collection<T> positions) {
        pending = new ArrayDeque<>(positions);
    }

    public void drain(int budget, Predicate<T> checkIfLoaded) {
        int attempts = Math.min(Math.max(0, budget), pending.size());
        for (int i = 0; i < attempts; i++) {
            T position = pending.getFirst();
            boolean complete = checkIfLoaded.test(position);
            pending.removeFirst();
            if (!complete) pending.addLast(position);
        }
    }
}
