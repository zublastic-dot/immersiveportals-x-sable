package qouteall.imm_ptl.core.lighting;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread admission of actual terrain programs, invalidated as a unit on pipeline destruction. */
final class PortalColoredShaderAdmission<W> {
    private static final int MAX_WORLDS = 16, MAX_PROGRAMS = 64;
    private record Entry(String pack, Map<Integer, Boolean> programs) {}
    private final Map<W, Entry> worlds = new IdentityHashMap<>();
    void observe(W world, String pack, int program, boolean carrier) {
        if (world == null || pack == null || program <= 0) return;
        Entry entry = worlds.get(world);
        if (entry == null || !entry.pack.equals(pack)) {
            if (entry == null && worlds.size() == MAX_WORLDS) return;
            entry = new Entry(pack, new HashMap<>()); worlds.put(world, entry);
        }
        if (entry.programs.size() < MAX_PROGRAMS || entry.programs.containsKey(program))
            entry.programs.put(program, carrier);
        else entry.programs.put(0, false); // Overflow cannot silently admit unverified programs.
    }
    boolean allows(W world, String pack) {
        Entry entry = worlds.get(world);
        return entry != null && Objects.equals(pack, entry.pack) && !entry.programs.isEmpty()
            && entry.programs.values().stream().allMatch(Boolean.TRUE::equals);
    }
    void clear() { worlds.clear(); }
}
