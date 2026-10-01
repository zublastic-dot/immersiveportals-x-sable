package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
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
    /** Transported scalar coordinates and immutable lightmaps for the known vanilla palette path. */
    public record VanillaData(Map<Pos, float[]> cells, PortalLightPalette nativePalette,
                              PortalLightPalette referencePalette) {
        public VanillaData {
            Objects.requireNonNull(nativePalette); Objects.requireNonNull(referencePalette);
            var copy = new HashMap<Pos, float[]>();
            cells.forEach((pos, value) -> {
                if (value == null || value.length != 3 || !Float.isFinite(value[0])
                        || !Float.isFinite(value[1]) || !Float.isFinite(value[2])
                        || value[0] < 0 || value[0] > 15 || value[1] < 0 || value[1] > 15
                        || value[2] < 0 || value[2] > 1)
                    throw new IllegalArgumentException("Vanilla portal cells require sky, block and replacement weight");
                copy.put(pos, value.clone());
            });
            cells = Map.copyOf(copy);
        }
        boolean matches(VanillaData other) {
            return other != null && nativePalette == other.nativePalette
                && referencePalette == other.referencePalette && sameOffsets(cells, other.cells);
        }
    }
    public record Region(ClientLevel world, Pos min, Map<Pos, float[]> offsets,
                         Map<Pos, float[]> ambientOffsets, VanillaData vanilla) {
        public Region {
            Objects.requireNonNull(min);
            if (offsets == ambientOffsets) {
                offsets = Map.copyOf(offsets); ambientOffsets = offsets;
            } else {
                if (!offsets.keySet().equals(ambientOffsets.keySet()))
                    throw new IllegalArgumentException("Portal light banks must cover the same cells");
                offsets = Map.copyOf(offsets); ambientOffsets = Map.copyOf(ambientOffsets);
            }
            if (vanilla != null && !offsets.keySet().equals(vanilla.cells.keySet()))
                throw new IllegalArgumentException("Vanilla portal metadata must cover the light banks");
        }
        /** Compatibility for consumers of the total and ambient banks. */
        public Region(ClientLevel world, Pos min, Map<Pos, float[]> offsets,
                      Map<Pos, float[]> ambientOffsets) {
            this(world, min, offsets, ambientOffsets, null);
        }
        /** Compatibility for total-only callers; runtime publishes the actual ambient split. */
        public Region(ClientLevel world, Pos min, Map<Pos, float[]> offsets) {
            this(world, min, offsets, offsets);
        }
        boolean matches(Pos nextMin, Map<Pos, float[]> total, Map<Pos, float[]> ambient) {
            return matches(nextMin, total, ambient, null);
        }
        boolean matches(Pos nextMin, Map<Pos, float[]> total, Map<Pos, float[]> ambient, VanillaData nextVanilla) {
            return min.equals(nextMin) && sameOffsets(offsets, total) && sameOffsets(ambientOffsets, ambient)
                && (vanilla == null ? nextVanilla == null : vanilla.matches(nextVanilla));
        }
    }
    // Value identity survives unloading/recreation of the destination portal entity.
    // The sample mapping also changes when either endpoint moves or is retargeted.
    private record Aperture(ClientLevel world, ClientLevel source, Map<Pos, Pos> samples, Pos inward) {}
    private record Endpoint(UUID portal, ResourceKey<Level> target, boolean destination) {}
    private static final class Entry {
        PortalLightSnapshot.Snapshot snapshot;
        PortalLightPalette nativePalette, incomingPalette, referencePalette;
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
    // Report at most 32 current endpoint states. Continuous physical motion must
    // not print a new rejection on every tick merely because its angle changed.
    private static final Map<Endpoint, Boolean> TARGET_ORIENTATIONS = new HashMap<>();
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
        REGIONS.clear(); ENTRIES.clear(); PALETTES.clear(); REASONS.clear(); TARGET_ORIENTATIONS.clear();
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
        var observedOrientations = new HashSet<Endpoint>();
        for (Portal p : portals) {
            ClientLevel world = (ClientLevel) p.level();
            ClientLevel remote = worlds.stream().filter(w -> w.dimension().equals(p.dimensionTo)).findFirst().orElse(null);
            if (remote == null) continue;
            // Either endpoint can keep both views alive while DH draws retained geometry.
            for (boolean destination : new boolean[]{false, true}) {
                ClientLevel target = destination ? remote : world, source = destination ? world : remote;
                Pos inward = receivingInward(p.getNormal(), p.getContentDirection(), destination);
                reportOrientation(p, target, destination, inward != null, observedOrientations);
                if (inward != null && apertures.size() < 16)
                    apertures.add(aperture(target, source, p, destination, inward));
            }
        }
        TARGET_ORIENTATIONS.keySet().retainAll(observedOrientations);
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
            PortalLightPalette reference = vanillaReference(a.world, a.source, nativePalette, incoming);
            if (previous != null && previous.field() == result.field() && entry.nativePalette == nativePalette
                    && entry.incomingPalette == incoming && entry.referencePalette == reference
                    && REGIONS.containsKey(a)) continue;
            if (entry.nativePalette != nativePalette || entry.incomingPalette != incoming || entry.paletteOffsets == null)
                entry.paletteOffsets = nativePalette.offsetTable(incoming);
            entry.nativePalette = nativePalette; entry.incomingPalette = incoming; entry.referencePalette = reference;
            var offsets = new HashMap<Pos, float[]>();
            Map<Pos, float[]> vanillaCells = reference == null ? null : new HashMap<>();
            // Most dark fields have no incoming block light: share their maps/arrays
            // until the first cell actually needs a distinct ambient bank.
            Map<Pos, float[]> ambientOffsets = null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (var cell : result.field().cells().entrySet()) {
                Pos p = cell.getKey(); Light light = cell.getValue();
                float[] delta = entry.paletteOffsets[light.sky() * 16 + light.block()];
                float weight = result.field().replacement().get(p);
                if (vanillaCells != null) vanillaCells.put(p, new float[]{light.sky(), light.block(), weight});
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
            VanillaData vanilla = vanillaCells == null ? null : new VanillaData(vanillaCells, nativePalette, reference);
            Region old = REGIONS.get(a);
            if (old == null || !old.matches(min, offsets, ambientOffsets, vanilla)) {
                REGIONS.put(a, new Region(a.world, min, offsets, ambientOffsets, vanilla));
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

    private static PortalLightPalette vanillaReference(ClientLevel world, ClientLevel source,
                                                       PortalLightPalette nativePalette, PortalLightPalette incoming) {
        if (!vanillaPair(world.dimension(), source.dimension(), world.dimensionType().effectsLocation(),
                source.dimensionType().effectsLocation(), world.dimensionType().ambientLight(),
                source.dimensionType().ambientLight(), world.effects().forceBrightLightmap(),
                source.effects().forceBrightLightmap())) return null;
        // Both directions use the same metadata-selected reference. Day, gamma,
        // palette flicker and camera crossing cannot flip it by changing RGB floors.
        return world.dimension().equals(Level.OVERWORLD) ? nativePalette : incoming;
    }

    static boolean vanillaPair(ResourceKey<Level> world, ResourceKey<Level> source,
                               ResourceLocation worldEffects, ResourceLocation sourceEffects,
                               float worldAmbient, float sourceAmbient,
                               boolean worldForceBright, boolean sourceForceBright) {
        if (worldForceBright || sourceForceBright || !Float.isFinite(worldAmbient)
                || !Float.isFinite(sourceAmbient)) return false;
        boolean worldOverworld = world.equals(Level.OVERWORLD) && source.equals(Level.NETHER);
        boolean sourceOverworld = source.equals(Level.OVERWORLD) && world.equals(Level.NETHER);
        if (!worldOverworld && !sourceOverworld) return false;
        return worldEffects.equals(worldOverworld ? BuiltinDimensionTypes.OVERWORLD_EFFECTS : BuiltinDimensionTypes.NETHER_EFFECTS)
            && sourceEffects.equals(worldOverworld ? BuiltinDimensionTypes.NETHER_EFFECTS : BuiltinDimensionTypes.OVERWORLD_EFFECTS)
            && (worldOverworld ? worldAmbient < sourceAmbient : sourceAmbient < worldAmbient);
    }

    private static boolean eligible(Portal p) {
        return !p.isRemoved() && !p.getIsGlobal() && p.getPortalShape() instanceof RectangularPortalShape
            && Math.abs(p.getScaling() - 1) < 1e-6 && p.getWidth() <= 30 && p.getHeight() <= 30;
    }
    private static boolean axisAligned(Vec3 n) {
        return Double.isFinite(n.x) && Double.isFinite(n.y) && Double.isFinite(n.z)
            && Math.max(Math.abs(n.x), Math.max(Math.abs(n.y), Math.abs(n.z))) > 0.999999;
    }
    /** Only the receiving voxel grid must be cardinal; source positions use the full transform. */
    static Pos receivingInward(Vec3 normal, Vec3 contentDirection, boolean destination) {
        Vec3 direction = destination ? contentDirection : normal;
        if (!axisAligned(direction)) return null;
        Pos inward = new Pos((int) Math.round(direction.x), (int) Math.round(direction.y), (int) Math.round(direction.z));
        return Math.abs(inward.x()) + Math.abs(inward.y()) + Math.abs(inward.z()) == 1 ? inward : null;
    }
    private static Aperture aperture(ClientLevel world, ClientLevel source, Portal p, boolean destination, Pos inward) {
        return new Aperture(world, source, apertureSamples(p.getWidth(), p.getHeight(), p.getNormal(),
            p::getPointInPlane, p::transformPoint, destination), inward);
    }
    @FunctionalInterface interface PlanePoint { Vec3 at(double u, double v); }
    static Map<Pos, Pos> apertureSamples(double width, double height, Vec3 normal, PlanePoint planePoint,
                                        java.util.function.UnaryOperator<Vec3> transform, boolean destination) {
        var samples = new HashMap<Pos, Pos>();
        for (double v = -height / 2 + .5; v < height / 2; v++)
            for (double u = -width / 2 + .5; u < width / 2; u++) {
                Vec3 plane = planePoint.at(u, v);
                Pos here = pos(plane.add(normal)), there = pos(transform.apply(plane.subtract(normal)));
                samples.put(destination ? there : here, destination ? here : there);
            }
        // Snapshot/buildTopology still proves all receiving cells occupy one integer
        // plane. No normal rounding or larger angular tolerance relaxes that proof.
        return Map.copyOf(samples);
    }
    private static void reportOrientation(Portal portal, ClientLevel target, boolean destination,
                                          boolean supported, Set<Endpoint> observed) {
        Endpoint endpoint = new Endpoint(portal.getUUID(), target.dimension(), destination);
        if (!TARGET_ORIENTATIONS.containsKey(endpoint) && TARGET_ORIENTATIONS.size() >= 32) return;
        observed.add(endpoint);
        Boolean previous = TARGET_ORIENTATIONS.put(endpoint, supported);
        if (!supported && !Boolean.FALSE.equals(previous)) LogUtils.getLogger().info(
            "[IP portal light] portal {} target {}: unsupported receiving orientation {}; remote source orientation does not reject a cardinal target",
            portal.getUUID(), target.dimension().location(), destination ? portal.getContentDirection() : portal.getNormal());
        else if (supported && Boolean.FALSE.equals(previous)) LogUtils.getLogger().info(
            "[IP portal light] portal {} target {}: receiving orientation supported again",
            portal.getUUID(), target.dimension().location());
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
