package ipl.sable.diagnostics;

import java.util.UUID;
import java.util.function.LongSupplier;

/** Pure, bounded admission policy. No world access and no persistent configuration. */
public final class IgnitionTraceWindow {
    public static final int MAX_ATTEMPTS = 8;
    public static final int MAX_EVENTS = 64;
    public static final int MAX_SECONDS = 120;
    public static final int MAX_RADIUS = 32;
    public final UUID token, player;
    public final String dimension;
    public final int x, y, z, radius;
    private final long started, duration;
    private final LongSupplier clock;
    private final int limit;
    private int attempts;
    private boolean stopped;

    public IgnitionTraceWindow(UUID token, UUID player, String dimension,
        int x, int y, int z, int radius, int attempts, int seconds, LongSupplier clock) {
        if (token == null || player == null || dimension == null || dimension.isBlank()
            || dimension.length() > 256 || radius < 0 || radius > MAX_RADIUS
            || attempts < 1 || attempts > MAX_ATTEMPTS || seconds < 1 || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("Invalid bounded ignition trace scope");
        this.token = token; this.player = player; this.dimension = dimension;
        this.x = x; this.y = y; this.z = z; this.radius = radius; this.limit = attempts;
        this.clock = clock; this.started = clock.getAsLong(); this.duration = seconds * 1_000_000_000L;
    }

    public synchronized boolean alive() {
        return !stopped && clock.getAsLong() - started < duration;
    }
    public synchronized boolean accepts(UUID player, String dimension, int x, int y, int z) {
        return alive() && attempts < limit && this.player.equals(player) && this.dimension.equals(dimension)
            && Math.abs((long) x - this.x) <= radius && Math.abs((long) y - this.y) <= radius
            && Math.abs((long) z - this.z) <= radius;
    }
    public synchronized Attempt begin(UUID player, String dimension, int x, int y, int z) {
        return accepts(player, dimension, x, y, z) ? new Attempt(++attempts) : null;
    }
    public synchronized void stop() { stopped = true; }
    public synchronized String status() {
        return "token=" + token + " active=" + (alive() && attempts < limit)
            + " attempts=" + attempts + "/" + limit + " player=" + player + " dimension=" + dimension
            + " target=" + x + "," + y + "," + z + " radius=" + radius;
    }
    public final class Attempt {
        public final int number;
        private int events;
        private boolean closed;
        private Attempt(int number) { this.number = number; }
        public synchronized boolean event() {
            if (closed || !alive() || events >= MAX_EVENTS) return false;
            events++;
            return true;
        }
        public synchronized void close() { closed = true; }
    }
}
