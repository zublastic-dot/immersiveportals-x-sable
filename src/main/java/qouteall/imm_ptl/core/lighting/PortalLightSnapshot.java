package qouteall.imm_ptl.core.lighting;

import java.util.HashMap;
import java.util.Map;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** A verified enclosure can outlive vanilla chunks, just as the geometry drawn by DH does. */
public final class PortalLightSnapshot {
    public record Sample(Cell cell, Light light) {
        public static final Sample UNKNOWN = new Sample(Cell.UNKNOWN, new Light(0, 0));
    }
    public interface Reader { Sample read(Pos position); }
    public record Snapshot(Map<Pos, Pos> aperture, Map<Pos, Sample> geometry, Map<Pos, Sample> sources) {}
    public record Update(Result field, Snapshot snapshot, boolean usedCache) {}

    private PortalLightSnapshot() {}

    public static Update update(Reader target, Reader source, Map<Pos, Pos> aperture, Snapshot previous) {
        // Portal motion/retargeting cannot reuse a proof for a different aperture.
        if (previous != null && !previous.aperture.equals(aperture)) previous = null;
        var geometry = new Reads(target, previous == null ? Map.of() : previous.geometry);
        var sources = new Reads(source, previous == null ? Map.of() : previous.sources);
        var seeds = new HashMap<Pos, Light>();
        for (var entry : aperture.entrySet()) {
            Sample sample = sources.read(entry.getValue());
            if (sample.cell == Cell.UNKNOWN) return rejected("unloaded aperture sample");
            if (sample.cell == Cell.OPEN) seeds.merge(entry.getKey(), sample.light.step(), Light::max);
        }
        Result field = solve(p -> geometry.read(p).cell, seeds, 4096, 32);
        if (!field.enclosed()) return new Update(field, null, geometry.usedCache || sources.usedCache);
        // Never complete a newly changed enclosure with a stale, partly unloaded wall.
        if (geometry.changed && geometry.usedCache) return rejected("changed incomplete geometry");
        return new Update(field, new Snapshot(Map.copyOf(aperture), Map.copyOf(geometry.readings),
            Map.copyOf(sources.readings)), geometry.usedCache || sources.usedCache);
    }

    private static Update rejected(String reason) {
        return new Update(new Result(Map.of(), reason), null, false);
    }

    private static final class Reads {
        final Reader live;
        final Map<Pos, Sample> previous;
        final Map<Pos, Sample> readings = new HashMap<>();
        boolean usedCache, changed;

        Reads(Reader live, Map<Pos, Sample> previous) { this.live = live; this.previous = previous; }

        Sample read(Pos position) {
            return readings.computeIfAbsent(position, p -> {
                Sample current = live.read(p), old = previous.get(p);
                if (current.cell == Cell.UNKNOWN && old != null) {
                    usedCache = true;
                    return old;
                }
                if (old != null && current.cell != Cell.UNKNOWN && current.cell != old.cell) changed = true;
                return current;
            });
        }
    }
}
