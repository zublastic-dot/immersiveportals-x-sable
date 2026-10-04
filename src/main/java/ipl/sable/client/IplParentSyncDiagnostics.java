package ipl.sable.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded missing-allocation diagnostics, independent of retry and handoff execution. */
public final class IplParentSyncDiagnostics {
    public static final long RETRY_MILLIS = 30_000;
    private static final long FIRST_WARNING_MILLIS = 1_000, STALLED_MILLIS = 5_000,
        REPEAT_EXPIRY_MILLIS = 300_000;
    private static final int MAX_IDENTITIES = 256;
    private final Map<UUID, Missing> missing = new LinkedHashMap<>();

    public record PendingStamp(String parentDimId, long queuedAtMs) {
        public PendingStamp withLatestParent(String parent) { return new PendingStamp(parent, queuedAtMs); }
        public boolean expired(long now) { return now - queuedAtMs > RETRY_MILLIS; }
    }
    public record Notice(String phase, UUID id, String parent, String reason, long missingForMs, long lookups) {}
    private static final class Missing {
        final long first;
        long lookups, lastExpiry;
        boolean firstWarned, stalledWarned, expiredWarned;
        String reason;
        Missing(long first, String reason) { this.first = first; this.reason = reason; }
    }
    private Missing state(UUID id, long now, String reason) {
        var current = missing.get(id);
        if (current == null) {
            if (missing.size() == MAX_IDENTITIES) missing.remove(missing.keySet().iterator().next());
            current = new Missing(now, reason);missing.put(id, current);
        }
        current.reason = reason;
        return current;
    }
    /** A normal RPC/start-tracking race stays quiet for its first second. */
    public Notice missing(UUID id, String parent, String reason, long now) {
        var current = state(id, now, reason);current.lookups++;
        long age = Math.max(0, now - current.first);
        if (!current.firstWarned && age >= FIRST_WARNING_MILLIS) {
            current.firstWarned = true;return notice("allocation delayed", id, parent, current, age);
        }
        if (!current.stalledWarned && age >= STALLED_MILLIS) {
            current.stalledWarned = true;return notice("allocation stalled", id, parent, current, age);
        }
        return null;
    }
    /** Fresh server stamps may start a new attempt; they must not flood expiry warnings either. */
    public Notice expired(UUID id, String parent, long now) {
        var current = missing.get(id);
        if (current == null) current = state(id, now, "deferred parent stamp");
        if (current.expiredWarned && now - current.lastExpiry < REPEAT_EXPIRY_MILLIS) return null;
        current.expiredWarned = true;current.lastExpiry = now;
        return notice("retry expired", id, parent, current, Math.max(0, now - current.first));
    }
    private static Notice notice(String phase, UUID id, String parent, Missing current, long age) {
        return new Notice(phase, id, parent, current.reason, age, current.lookups);
    }
    public void recovered(UUID id) { missing.remove(id); }
    public void clear() { missing.clear(); }
    public int trackedIdentities() { return missing.size(); }
}
