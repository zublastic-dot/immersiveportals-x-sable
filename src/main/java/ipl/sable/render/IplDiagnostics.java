package ipl.sable.render;

import java.util.HashSet;
import java.util.Set;

/** Routine bring-up probes require an explicit JVM opt-in, independent of logger configuration. */
public final class IplDiagnostics {
    private static final boolean VERBOSE = Boolean.getBoolean("ipl.diagnostics.verbose");

    private IplDiagnostics() {}

    public static boolean verbose() { return VERBOSE; }

    /** Finite exact-key deduplication; alternating shader programs cannot defeat the limit. */
    public static final class BoundedKeys {
        private final int limit;
        private final Set<String> keys = new HashSet<>();

        public BoundedKeys(int limit) {
            if (limit < 1 || limit > 256) throw new IllegalArgumentException("Diagnostic key limit must be 1..256");
            this.limit = limit;
        }

        public synchronized boolean first(String key) {
            if (keys.size() >= limit) return false;
            return keys.add(key);
        }
    }
}
