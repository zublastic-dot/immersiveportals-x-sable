package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.util.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Client-only trial. No light storage writes, chunk loads, server hooks or room coordinates. */
public final class PortalLighting {
    public record Region(ClientLevel world, Pos min, Map<Pos, float[]> offsets,
                         Map<Pos, float[]> ambientOffsets) {
        public Region {
            Objects.requireNonNull(min);
            if (offsets == ambientOffsets) {
                offsets = Map.copyOf(offsets); ambientOffsets = offsets;
            } else {
                if (!offsets.keySet().equals(ambientOffsets.keySet()))
                    throw new IllegalArgumentException("Portal light banks must cover the same cells");
                offsets = Map.copyOf(offsets); ambientOffsets = Map.copyOf(ambientOffsets);
            }
        }
        /** Compatibility for total-only callers; runtime publishes the actual ambient split. */
        public Region(ClientLevel world, Pos min, Map<Pos, float[]> offsets) {
            this(world, min, offsets, offsets);
        }
        boolean matches(Pos nextMin, Map<Pos, float[]> total, Map<Pos, float[]> ambient) {
            return min.equals(nextMin) && sameOffsets(offsets, total) && sameOffsets(ambientOffsets, ambient);
        }
    }
    // Value identity survives unloading/recreation of the destination portal entity.
    // The sample mapping also changes when either endpoint moves or is retargeted.
    private record Aperture(ClientLevel world, ClientLevel source, Map<Pos, Pos> samples, Pos inward) {}
    private static final class Entry {
        PortalLightSnapshot.Snapshot snapshot;
        PortalLightPalette nativePalette, incomingPalette;
        float[][] paletteOffsets;
        boolean geometryDirty = true;
        int retryAt;
        Set<Long> geometryChunks = Set.of(), sourceChunks = Set.of();
    }
    private static final Map<ClientLevel, PortalLightPalette> PALETTES = new WeakHashMap<>();
    private static final Map<ClientLevel, Long> REVISIONS = new WeakHashMap<>();
    private static final Map<Aperture, Region> REGIONS = new HashMap<>();
    private static final Map<Aperture, Entry> ENTRIES = new HashMap<>();
    private static final Map<Aperture, String> REASONS = new HashMap<>();
    private static int tick;
    private static long revision, lastUpdateNanos;
    private static int lastTopologyBuilds, lastPropagationCount, lastPublishedRegions;
    public static final boolean ENABLED = !Boolean.getBoolean("imm_ptl.disablePortalLightTransport");
    private PortalLighting() {}

    public static void init() {
        // Sable dynamic lights refresh at LevelTickEvent.Post. IP's older tick event
        // fires before that; sample at the end of the whole client tick instead.
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> update());
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ClientLevel level)
                chunkChanged(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ClientLevel level)
                chunkChanged(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        });
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class, event -> {
            clear(); PortalLightGpu.clear();
        });
    }
    private static void clear() {
        REGIONS.clear(); ENTRIES.clear(); PALETTES.clear(); REASONS.clear();
        REVISIONS.clear(); revision++;
    }
    public static void capture(ClientLevel level, int[] pixels) {
        if (level == null) return;
        PortalLightPalette previous = PALETTES.get(level);
        if (previous == null || !previous.matches(pixels)) PALETTES.put(level, new PortalLightPalette(pixels));
    }
    public static long revision() { return revision; }
    public static long revision(ClientLevel level) { return REVISIONS.getOrDefault(level, 0L); }
    public static long lastUpdateNanos() { return lastUpdateNanos; }
    public static int lastTopologyBuilds() { return lastTopologyBuilds; }
    public static int lastPropagationCount() { return lastPropagationCount; }
    public static int lastPublishedRegions() { return lastPublishedRegions; }
    public static List<Region> regions(ClientLevel level) {
        if (!ENABLED || IrisInterface.invoker.isShaders()) return List.of();
        return REGIONS.values().stream().filter(r -> r.world == level)
            .sorted(Comparator.comparingInt((Region r) -> r.min.x())
                .thenComparingInt(r -> r.min.y()).thenComparingInt(r -> r.min.z())).limit(4).toList();
    }

    /** Called only for actual air/solid classification changes, never light-engine writes. */
    public static void blockChanged(ClientLevel world, BlockPos position) {
        Pos p = new Pos(position.getX(), position.getY(), position.getZ());
        ENTRIES.forEach((aperture, entry) -> {
            if (aperture.world == world && (entry.snapshot == null || entry.snapshot.geometry().containsKey(p))) {
                entry.geometryDirty = true; entry.retryAt = 0;
            }
        });
    }
    private static void chunkChanged(ClientLevel world, int x, int z) {
        ENTRIES.forEach((aperture, entry) -> {
            long chunk = chunkKey(x, z);
            if (aperture.world == world && (entry.snapshot == null || entry.geometryChunks.contains(chunk))) {
                entry.geometryDirty = true; entry.retryAt = 0;
            }
            if (aperture.source == world && entry.sourceChunks.contains(chunk)) entry.retryAt = 0;
        });
    }
    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static Set<Long> chunks(Collection<Pos> positions) {
        Set<Long> result = new HashSet<>();
        for (Pos p : positions) result.add(chunkKey(p.x() >> 4, p.z() >> 4));
        return Set.copyOf(result);
    }
    private static void changed(ClientLevel world) {
        revision++; REVISIONS.put(world, revision);
    }
    private static void remove(Aperture aperture) {
        if (REGIONS.remove(aperture) != null) changed(aperture.world);
    }

    private static void update() {
        long started = System.nanoTime();
        lastTopologyBuilds = lastPropagationCount = lastPublishedRegions = 0;
        try { updateFields(); }
        finally { lastUpdateNanos = System.nanoTime() - started; }
    }
    private static void updateFields() {
        Minecraft mc = Minecraft.getInstance(); tick++;
        if (mc.level == null || !ClientWorldLoader.getIsInitialized()) {
            if (!ENTRIES.isEmpty() || !PALETTES.isEmpty()) clear();
            return;
        }
        if (!ENABLED || IrisInterface.invoker.isShaders() || mc.isPaused()) return;
        var worlds = new ArrayList<>(ClientWorldLoader.getClientWorlds());
        PortalLightGpu.retain(worlds);
        var portals = new ArrayList<Portal>();
        for (var world : worlds) for (var entity : world.entitiesForRendering()) {
            if (entity instanceof Portal p && eligible(p)) portals.add(p);
        }
        portals.sort(Comparator.comparing(Portal::getUUID));
        var apertures = new LinkedHashSet<Aperture>();
        for (Portal p : portals) {
            ClientLevel world = (ClientLevel) p.level();
            ClientLevel remote = worlds.stream().filter(w -> w.dimension().equals(p.dimensionTo)).findFirst().orElse(null);
            if (remote == null) continue;
            // Either endpoint can keep both views alive while DH draws retained geometry.
            if (apertures.size() < 16) apertures.add(aperture(world, remote, p, false));
            if (apertures.size() < 16) apertures.add(aperture(remote, world, p, true));
        }
        for (Aperture old : new ArrayList<>(ENTRIES.keySet())) if (!apertures.contains(old)) {
            remove(old); ENTRIES.remove(old); REASONS.remove(old);
        }
        if (apertures.isEmpty()) return;
        var paletteWorlds = Collections.newSetFromMap(new IdentityHashMap<ClientLevel, Boolean>());
        for (Aperture a : apertures) { paletteWorlds.add(a.world); paletteWorlds.add(a.source); }
        for (ClientLevel world : paletteWorlds) refreshPalette(world);
        // Every field observes current sources this tick; only topology rebuilds are
        // expensive. Item movement and light storage updates never dirty topology.
        for (Aperture a : apertures) {
            Entry entry = ENTRIES.computeIfAbsent(a, ignored -> {
                Entry created = new Entry(); created.sourceChunks = chunks(a.samples.values()); return created;
            });
            if (entry.snapshot == null && tick < entry.retryAt) continue;
            var previous = entry.snapshot;
            var result = PortalLightSnapshot.update(p -> sample(a.world, p, false),
                p -> sample(a.source, p, true), a.samples, a.inward, previous, entry.geometryDirty);
            entry.geometryDirty = false;
            if (!result.field().available()) {
                entry.snapshot = null; entry.retryAt = tick + 5; remove(a);
                report(a, result.field().reason()); continue;
            }
            entry.snapshot = result.snapshot();
            if (previous == null || previous.topology() != entry.snapshot.topology()) {
                lastTopologyBuilds++; entry.geometryChunks = chunks(entry.snapshot.geometry().keySet());
            }
            if (previous == null || previous.field() != result.field()) lastPropagationCount++;
            PortalLightPalette nativePalette = PALETTES.get(a.world), incoming = PALETTES.get(a.source);
            if (nativePalette == null || incoming == null) {
                remove(a); report(a, "lightmap unavailable"); continue;
            }
            if (previous != null && previous.field() == result.field() && entry.nativePalette == nativePalette
                    && entry.incomingPalette == incoming && REGIONS.containsKey(a)) continue;
            if (entry.nativePalette != nativePalette || entry.incomingPalette != incoming || entry.paletteOffsets == null)
                entry.paletteOffsets = nativePalette.offsetTable(incoming);
            entry.nativePalette = nativePalette; entry.incomingPalette = incoming;
            var offsets = new HashMap<Pos, float[]>();
            // Most dark fields have no incoming block light: share their maps/arrays
            // until the first cell actually needs a distinct ambient bank.
            Map<Pos, float[]> ambientOffsets = null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (var cell : result.field().cells().entrySet()) {
                Pos p = cell.getKey(); Light light = cell.getValue();
                float[] delta = entry.paletteOffsets[light.sky() * 16 + light.block()];
                float weight = result.field().replacement().get(p);
                float[] total = new float[]{delta[0] * weight, delta[1] * weight, delta[2] * weight};
                offsets.put(p, total);
                if (light.block() != 0 && ambientOffsets == null) ambientOffsets = new HashMap<>(offsets);
                if (ambientOffsets != null) {
                    float[] ambient = entry.paletteOffsets[light.sky() * 16];
                    ambientOffsets.put(p, light.block() == 0 ? total : new float[]{
                        ambient[0] * weight, ambient[1] * weight, ambient[2] * weight});
                }
                minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
            }
            Pos min = new Pos(minX, minY, minZ);
            if (ambientOffsets == null) ambientOffsets = offsets;
            Region old = REGIONS.get(a);
            if (old == null || !old.matches(min, offsets, ambientOffsets)) {
                REGIONS.put(a, new Region(a.world, min, offsets, ambientOffsets));
                changed(a.world); lastPublishedRegions++;
            }
            report(a, "transport cells=" + offsets.size());
        }
    }
    static boolean sameOffsets(Map<Pos, float[]> a, Map<Pos, float[]> b) {
        if (a.size() != b.size()) return false;
        for (var entry : a.entrySet()) if (!Arrays.equals(entry.getValue(), b.get(entry.getKey()))) return false;
        return true;
    }

    private static boolean eligible(Portal p) {
        return !p.isRemoved() && !p.getIsGlobal() && p.getPortalShape() instanceof RectangularPortalShape
            && Math.abs(p.getScaling() - 1) < 1e-6 && p.getWidth() <= 30 && p.getHeight() <= 30
            && axisAligned(p.getNormal()) && axisAligned(p.getContentDirection());
    }
    private static boolean axisAligned(Vec3 n) {
        return Math.max(Math.abs(n.x), Math.max(Math.abs(n.y), Math.abs(n.z))) > 0.999999;
    }
    private static Aperture aperture(ClientLevel world, ClientLevel source, Portal p, boolean destination) {
        var samples = new HashMap<Pos, Pos>(); Vec3 n = p.getNormal();
        for (double v = -p.getHeight() / 2 + .5; v < p.getHeight() / 2; v++)
            for (double u = -p.getWidth() / 2 + .5; u < p.getWidth() / 2; u++) {
                Vec3 plane = p.getPointInPlane(u, v);
                Pos here = pos(plane.add(n)), there = pos(p.transformPoint(plane.subtract(n)));
                samples.put(destination ? there : here, destination ? here : there);
            }
        Vec3 direction = destination ? p.getContentDirection() : n;
        Pos inward = new Pos((int) Math.round(direction.x), (int) Math.round(direction.y), (int) Math.round(direction.z));
        return new Aperture(world, source, Map.copyOf(samples), inward);
    }
    private static Pos pos(Vec3 point) {
        BlockPos p = BlockPos.containing(point); return new Pos(p.getX(), p.getY(), p.getZ());
    }
    private static PortalLightSnapshot.Sample sample(ClientLevel world, Pos pos, boolean source) {
        BlockPos block = new BlockPos(pos.x(), pos.y(), pos.z());
        if (world.isOutsideBuildHeight(block) || !world.hasChunkAt(block)) return PortalLightSnapshot.Sample.UNKNOWN;
        var state = world.getBlockState(block);
        boolean open = source ? state.getLightBlock(world, block) < 15 : state.isAir();
        return new PortalLightSnapshot.Sample(open ? Cell.OPEN : Cell.CLOSED,
            new Light(world.getBrightness(LightLayer.SKY, block), world.getBrightness(LightLayer.BLOCK, block)));
    }
    private static void refreshPalette(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance(); ClientLevel old = mc.level;
        // Resolve before changing level: helper construction checks Minecraft.level.
        var texture = ClientWorldLoader.getDimensionRenderHelper(level.dimension()).lightmapTexture;
        try { mc.level = level; texture.updateLightTexture(0); }
        finally { mc.level = old; }
    }
    private static void report(Aperture aperture, String reason) {
        if (!reason.equals(REASONS.put(aperture, reason))) LogUtils.getLogger().info(
            "[IP portal light] {} aperture {}: {}", aperture.world.dimension().location(), aperture.samples.hashCode(), reason);
    }
}
