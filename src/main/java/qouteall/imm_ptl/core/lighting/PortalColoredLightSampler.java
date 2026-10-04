package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

/** Client-tick service. Bounded work is shared by all endpoint requests, independent of portal rendering. */
public final class PortalColoredLightSampler {
    public static final int MAX_ENTRIES = 16, MAX_READS_PER_TICK = 8192, MAX_STEPS_PER_TICK = 65_536;
    public static final long MAX_NANOS_PER_TICK = 2_000_000;
    private static final int REFRESH_TICKS = 10, EXPIRE_TICKS = 40;
    public record Result(boolean supported, boolean ready, float red, float green, float blue, String reason) {
        static Result pending() { return new Result(true, false, 0, 0, 0, "capturing native RGB"); }
        static Result unavailable(String reason) { return new Result(false, false, 0, 0, 0, reason); }
    }
    private static final Engine<ClientLevel> ENGINE = new Engine<>(PortalColoredLightAdapter::reader);

    public static Result sample(ClientLevel source, Collection<BlockPos> sourcePositions, long tick) {
        if (source == null || !PortalColoredLightAdapter.available()) {
            ENGINE.clear(); return Result.unavailable(PortalColoredLightAdapter.reason());
        }
        if (sourcePositions.size() > PortalColoredLightField.MAX_APERTURE)
            return Result.unavailable("unsupported aperture");
        List<Pos> positions = sourcePositions.stream().map(p -> new Pos(p.getX(), p.getY(), p.getZ())).toList();
        Result result = ENGINE.sample(source, positions, tick);
        if (!PortalColoredLightAdapter.available()) { ENGINE.clear(); return Result.unavailable(PortalColoredLightAdapter.reason()); }
        return result;
    }
    /** Advance previously requested snapshots without forcing a receiving mesh publication this tick. */
    public static void advance(long tick) {
        if (!PortalColoredLightAdapter.available()) { ENGINE.clear(); return; }
        ENGINE.pump(tick);
        if (!PortalColoredLightAdapter.available()) ENGINE.clear();
    }
    public static void clear() { ENGINE.clear(); }
    public static String status() {
        return PortalColoredLightAdapter.reason() + "; entries=" + ENGINE.entries.size()
            + "; reads=" + ENGINE.lastReads + "/" + MAX_READS_PER_TICK
            + "; steps=" + ENGINE.lastSteps + "/" + MAX_STEPS_PER_TICK
            + "; samplerNanos=" + ENGINE.lastNanos + "/" + MAX_NANOS_PER_TICK
            + "; deadlineReached=" + ENGINE.deadlineReached
            + "; snapshots=" + ENGINE.completed + "; volumeCap=" + PortalColoredLightField.MAX_VOXELS
            + "; knownCaptureVoxels=" + ENGINE.entries.values().stream().mapToLong(e -> e.volume).sum()
            + "; sources=" + ENGINE.describe(world -> world.dimension().location().toString());
    }

    /** Identity-keyed scheduling is pure so replacements, removals and starvation can be tested without a client. */
    static final class Engine<W> {
        final Function<W, PortalColoredLightField.Reader> readers;
        final LongSupplier nanoTime;
        final LinkedHashMap<Key<W>, Entry> entries = new LinkedHashMap<>(16, .75f, true);
        long lastTick = Long.MIN_VALUE, completed, lastNanos;
        int lastReads, lastSteps, cursor;
        boolean deadlineReached;
        Engine(Function<W, PortalColoredLightField.Reader> readers) { this(readers, System::nanoTime); }
        Engine(Function<W, PortalColoredLightField.Reader> readers, LongSupplier nanoTime) {
            this.readers = readers; this.nanoTime = Objects.requireNonNull(nanoTime);
        }
        Result sample(W source, Collection<Pos> positions, long tick) {
            if (positions.isEmpty() || positions.size() > PortalColoredLightField.MAX_APERTURE)
                return Result.unavailable("unsupported aperture");
            var key = new Key<>(source, Set.copyOf(positions));
            Entry entry = entries.get(key);
            if (entry == null) {
                if (entries.size() == MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
                entry = new Entry(); entries.put(key, entry);
            }
            entry.seen = tick;
            pump(tick);
            return entry.result;
        }
        void clear() {
            entries.clear(); lastTick = Long.MIN_VALUE; lastReads = lastSteps = cursor = 0;
            completed = lastNanos = 0; deadlineReached = false;
        }
        String describe(Function<W, String> worldName) {
            var output = new StringJoiner("; ", "[", "]");
            for (var item : entries.entrySet()) {
                Key<W> key = item.getKey(); Entry entry = item.getValue();
                int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
                int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
                for (Pos p : key.positions) {
                    minX = Math.min(minX,p.x()); minY = Math.min(minY,p.y()); minZ = Math.min(minZ,p.z());
                    maxX = Math.max(maxX,p.x()); maxY = Math.max(maxY,p.y()); maxZ = Math.max(maxZ,p.z());
                }
                Result result = entry.result;
                output.add(worldName.apply(key.world) + " " + minX + "," + minY + "," + minZ + ".."
                    + maxX + "," + maxY + "," + maxZ + " rgb=" + result.red + "/" + result.green + "/" + result.blue
                    + " ready=" + result.ready + " " + (entry.job == null ? entry.lastCapture : entry.job.captureStatus()));
            }
            return output.toString();
        }
        private void pump(long tick) {
            if (tick == lastTick) return;
            // A replaced client/world clock must not leave old refresh deadlines in the future.
            if (tick < lastTick) for (Entry entry : entries.values()) entry.nextRefresh = tick;
            lastTick = tick; lastReads = lastSteps = 0; lastNanos = 0; deadlineReached = false;
            entries.entrySet().removeIf(e -> tick - e.getValue().seen > EXPIRE_TICKS);
            var active = new ArrayList<>(entries.entrySet());
            if (active.isEmpty()) return;
            long started = nanoTime.getAsLong();
            java.util.function.BooleanSupplier withinDeadline = () -> nanoTime.getAsLong() - started < MAX_NANOS_PER_TICK;
            int jobsStarted = 0;
            boolean progressed;
            do {
                progressed = false;
                for (int offset = 0; offset < active.size() && withinDeadline.getAsBoolean(); offset++) {
                    var item = active.get((cursor + offset) % active.size());
                    Entry entry = item.getValue();
                    // Starting all sixteen volumes in one tick would allocate far beyond the time budget.
                    if (entry.job == null && entry.result.supported && tick >= entry.nextRefresh && jobsStarted == 0) {
                        jobsStarted++;
                        PortalColoredLightField.Reader reader = readers.apply(item.getKey().world);
                        if (reader == null) { entry.result = Result.unavailable("native RGB reader unavailable"); continue; }
                        try {
                            entry.job = new PortalColoredLightField.Job(reader, item.getKey().positions);
                            entry.volume = entry.job.volume();
                        }
                        catch (IllegalArgumentException bounds) { entry.result = Result.unavailable(bounds.getMessage()); }
                    }
                    if (entry.job == null) continue;
                    var work = entry.job.advance(Math.min(128, MAX_READS_PER_TICK - lastReads),
                        Math.min(512, MAX_STEPS_PER_TICK - lastSteps), withinDeadline);
                    lastReads += work.reads(); lastSteps += work.steps();
                    progressed |= work.reads() != 0 || work.steps() != 0;
                    if (work.complete()) {
                        var average = entry.job.average();
                        entry.result = new Result(true, true, average.red(), average.green(), average.blue(), "native RGB snapshot");
                        entry.lastCapture = entry.job.captureStatus();
                        entry.job = null; entry.nextRefresh = tick + REFRESH_TICKS; completed++;
                    }
                }
            } while (progressed && withinDeadline.getAsBoolean()
                && (lastReads < MAX_READS_PER_TICK || lastSteps < MAX_STEPS_PER_TICK));
            lastNanos = nanoTime.getAsLong() - started;
            deadlineReached = lastNanos >= MAX_NANOS_PER_TICK;
            cursor = (cursor + 1) % active.size();
        }
    }
    private static final class Entry {
        long seen, nextRefresh;
        PortalColoredLightField.Job job;
        int volume;
        Result result = Result.pending();
        String lastCapture = "not captured";
    }
    private static final class Key<W> {
        final W world;
        final Set<Pos> positions;
        Key(W world, Set<Pos> positions) { this.world = Objects.requireNonNull(world); this.positions = positions; }
        @Override public int hashCode() { return 31 * System.identityHashCode(world) + positions.hashCode(); }
        @Override public boolean equals(Object value) {
            return value instanceof Key<?> other && world == other.world && positions.equals(other.positions);
        }
    }
    private PortalColoredLightSampler() {}
}
