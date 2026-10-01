package ipl.sable.network;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Connection-scoped bookkeeping for replacing a discarded hosted client world. */
public final class HostedTrackingReset {
    private HostedTrackingReset() {}

    /** A cleanup is sent only after gameplay resumes on the same connection. */
    public static final class Client<C> {
        private C connection;
        private long revision;
        private long pending;

        public void onCleanup(C currentConnection) {
            if (currentConnection == null || connection != currentConnection) {
                clear();
                return;
            }
            pending = ++revision;
        }

        public void clear() {
            connection = null;
            pending = 0;
        }

        /** Zero means that no request should be sent. Multiple cleanups coalesce. */
        public long takeRequest(C currentConnection, boolean gameplayReady) {
            if (currentConnection == null) {
                clear();
                return 0;
            }
            if (connection != currentConnection) {
                // Remember the connection whose world we actually ticked. A cleanup
                // during login must not invalidate an unrelated new connection.
                connection = currentConnection;
                pending = 0;
                return 0;
            }
            if (!gameplayReady) return 0;
            long request = pending;
            pending = 0;
            return request;
        }
    }

    /** Cooldown delays the latest request; it never drops a newer cleanup. */
    public static final class Server {
        private final int intervalTicks;
        private long latest;
        private long pending;
        private long nextAllowedTick = Long.MIN_VALUE;

        public Server(int intervalTicks) {
            if (intervalTicks < 0) throw new IllegalArgumentException("negative interval");
            this.intervalTicks = intervalTicks;
        }

        public boolean request(long revision) {
            if (revision <= latest) return false;
            latest = revision;
            pending = revision;
            return true;
        }

        public long takeRequest(long tick) {
            if (pending == 0 || tick < nextAllowedTick) return 0;
            long request = pending;
            pending = 0;
            nextAllowedTick = tick + intervalTicks;
            return request;
        }
    }

    /**
     * A reset loses each tracked client allocation. Keep that obligation until the
     * ship is eligible for replay or normal tracking removes it. A single viewer
     * flag would lose ships whose portal visibility has not arrived yet.
     */
    public static final class Replay<S> {
        private final Set<S> pending = new HashSet<>();

        public void reset(Collection<S> currentlyTracked) {
            pending.clear();
            pending.addAll(currentlyTracked);
        }

        public boolean needsReplay(S ship) {
            return pending.contains(ship);
        }

        public void synced(S ship) {
            pending.remove(ship);
        }

        public void retainTracked(Predicate<S> stillTracked) {
            pending.removeIf(ship -> !stillTracked.test(ship));
        }

        public int size() {
            return pending.size();
        }
    }
}
