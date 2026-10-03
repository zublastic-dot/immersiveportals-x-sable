package qouteall.imm_ptl.core.teleportation;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Identity, not equals/hashCode: a native sound and its original stop key must stay paired. */
final class PortalSoundRegistry<S, W> {
    record Owner<W>(W world, long admittedTick) {}
    private final int limit;
    private final Map<S, Owner<W>> owners = new IdentityHashMap<>();

    PortalSoundRegistry(int limit) {
        if (limit < 1) throw new IllegalArgumentException("positive source limit required");
        this.limit = limit;
    }

    synchronized boolean bind(S sound, W world, long tick) {
        if (sound == null || world == null) return false;
        if (owners.containsKey(sound)) return true;
        if (owners.size() >= limit) return false;
        owners.put(sound, new Owner<>(world, tick));
        return true;
    }

    synchronized Owner<W> owner(S sound) { return owners.get(sound); }
    synchronized void moveEmitter(S sound, W world) {
        Owner<W> previous = owners.get(sound);
        if (previous != null && world != null && previous.world() != world) {
            owners.put(sound, new Owner<>(world, previous.admittedTick()));
        }
    }
    synchronized int size() { return owners.size(); }
    synchronized void clear() { owners.clear(); }

    synchronized void prune(Predicate<S> retained, Predicate<W> loaded, long tick) {
        owners.entrySet().removeIf(e -> !loaded.test(e.getValue().world())
            || (!retained.test(e.getKey()) && tick - e.getValue().admittedTick() > 40));
    }
}
