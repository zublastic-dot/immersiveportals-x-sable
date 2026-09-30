package qouteall.imm_ptl.core.compat.dh_compatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A successful local teleport authorizes one exact portal path, never a dimension-wide search. */
public final class DhTaaCrossing {
    public record Key(Object origin, Object destination, List<UUID> path) {
        public Key { path = List.copyOf(path); }
    }
    private Key key;
    private int frame;
    private long time;
    public boolean pending() { return key != null; }

    public void crossed(Object origin, Object destination, UUID portal, int frame, long now) {
        var path = new ArrayList<UUID>();
        if (key != null && this.frame == frame && Objects.equals(key.destination(), origin)) {
            path.addAll(key.path()); origin = key.origin();
        }
        path.add(portal);
        key = new Key(origin, destination, path); this.frame = frame; time = now;
    }
    public Key take(Object destination, int frame, long now) {
        Key result = key; key = null;
        return result != null && Objects.equals(result.destination(), destination)
            && frame - this.frame >= 0 && frame - this.frame <= 1
            && now >= time && now - time < 500_000_000L ? result : null;
    }
    public void clear() { key = null; }
}
