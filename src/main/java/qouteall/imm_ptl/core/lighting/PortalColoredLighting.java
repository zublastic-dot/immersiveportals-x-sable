package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.lang.reflect.Method;
import java.util.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Experimental, one-hop RGB transport. Published colors are render inputs, never light-engine seeds. */
public final class PortalColoredLighting {
    public static final int MAX_REGIONS = 4, MAX_REBUILDS_PER_TICK = 8, MAX_DIRTY_SECTIONS = 512;
    private record Aperture(ClientLevel target, ClientLevel source, Map<Pos, Pos> samples, Pos inward) {}
    private record Bounds(Pos min, Pos max) {
        boolean contains(double x, double y, double z) {
            return x >= min.x() && y >= min.y() && z >= min.z()
                && x < max.x()+1.0 && y < max.y()+1.0 && z < max.z()+1.0;
        }
    }
    private record Region(Aperture aperture, Map<Pos, Integer> rgb, Bounds bounds) {
        Region(Aperture aperture, Map<Pos, Integer> rgb) { this(aperture, rgb, PortalColoredLighting.bounds(rgb.keySet())); }
    }
    record FieldSummary(int cells, int coloredCells, int red, int green, int blue) {}
    public record CarrierBounds(Pos min, Pos max) {}
    private record DirtySection(ClientLevel world, int x, int y, int z) {}
    private static final class Entry {
        PortalLightSnapshot.Snapshot snapshot;
        Region region;
        float red, green, blue;
        boolean dirty = true;
    }
    private static final Map<Aperture, Entry> ENTRIES = new LinkedHashMap<>();
    private static final LinkedHashSet<DirtySection> DIRTY = new LinkedHashSet<>();
    private static final PortalColoredShaderAdmission<ClientLevel> SHADER_ADMISSION = new PortalColoredShaderAdmission<>();
    // Chunk workers read this immutable publication without touching live worlds or mutable caches.
    private static volatile List<Region> published = List.of();
    private static volatile String reason = "disabled";
    private static volatile long revision, lastUpdateNanos;
    private static int tick, rebuilt, dirtyHighWater;
    private static boolean initialized;
    private static Bridge bridge;
    private static boolean bridgeTried;
    private static volatile boolean renderFailed;

    private PortalColoredLighting() {}
    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> update());
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ClientLevel world) dirty(world);
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ClientLevel world) dirty(world);
        });
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class, event -> clear());
    }
    public static Map<String, Object> status() {
        return Map.ofEntries(Map.entry("enabled", IPGlobal.experimentalPortalColoredLighting), Map.entry("state", reason),
            Map.entry("regions", published.size()), Map.entry("revision", revision), Map.entry("updateNanos", lastUpdateNanos),
            Map.entry("pendingSections", DIRTY.size()), Map.entry("rebuiltLastTick", rebuilt),
            Map.entry("maxRegions", MAX_REGIONS), Map.entry("maxRebuildsPerTick", MAX_REBUILDS_PER_TICK),
            Map.entry("maxPendingSections", MAX_DIRTY_SECTIONS), Map.entry("pendingHighWater", dirtyHighWater),
            Map.entry("sampler", PortalColoredLightSampler.status()), Map.entry("fields", fieldStatus()));
    }
    private static List<Map<String, Object>> fieldStatus() {
        // At most four immutable fields; computed only on an explicit status request, never per vertex.
        return published.stream().map(region -> Map.<String, Object>of(
            "target", region.aperture.target.dimension().location().toString(),
            "source", region.aperture.source.dimension().location().toString(),
            "bounds", region.bounds.toString(), "rgb8", summarize(region.rgb))).toList();
    }
    static FieldSummary summarize(Map<Pos, Integer> cells) {
        int colored = 0, maximum = 0;
        for (int rgb : cells.values()) {
            if (rgb != 0) colored++;
            maximum = max(maximum, rgb);
        }
        return new FieldSummary(cells.size(), colored, maximum >>> 16 & 255, maximum >>> 8 & 255, maximum & 255);
    }
    public static void clear() {
        published = List.of(); ENTRIES.clear(); DIRTY.clear();
        SHADER_ADMISSION.clear();
        PortalColoredLightSampler.clear(); revision++; reason = "disabled";
    }
    public static void observeShaderProgram(ClientLevel world, int program, boolean carrier) {
        if (!IrisInterface.invoker.isRenderingShadowMap())
            SHADER_ADMISSION.observe(world, IrisInterface.invoker.getShaderpackName(), program, carrier);
    }
    public static void invalidateShaders() {
        SHADER_ADMISSION.clear();
        // Keep source snapshots, but revoke imported render fields until replacement programs prove admission.
        publish(List.of());
    }
    private static boolean shaderAllowed(ClientLevel world) {
        return !IrisInterface.invoker.isShaders()
            || SHADER_ADMISSION.allows(world, IrisInterface.invoker.getShaderpackName());
    }
    public static void blockChanged(ClientLevel world, BlockPos pos) {
        Pos p = new Pos(pos.getX(), pos.getY(), pos.getZ());
        ENTRIES.forEach((a, e) -> {
            if (a.target == world && (e.snapshot == null || e.snapshot.geometry().containsKey(p))) e.dirty = true;
        });
    }
    private static void dirty(ClientLevel world) {
        ENTRIES.forEach((a, e) -> { if (a.target == world) e.dirty = true; });
    }
    /** Scalar transport remains the fallback until this exact endpoint has an RGB publication. */
    public static boolean transports(ClientLevel target, ClientLevel source, Map<Pos, Pos> samples) {
        if (!IPGlobal.experimentalPortalColoredLighting || renderFailed || !shaderAllowed(target)) return false;
        return published.stream().anyMatch(r -> r.aperture.target == target && r.aperture.source == source
            && r.aperture.samples.equals(samples));
    }
    /** Render-pass scoped gate; a same-key replacement world must not inherit another world's colors. */
    public static boolean hasField(ClientLevel target) {
        return IPGlobal.experimentalPortalColoredLighting && !renderFailed && shaderAllowed(target)
            && published.stream().anyMatch(r -> r.aperture.target == target);
    }
    public static List<CarrierBounds> carrierBounds(ClientLevel target) {
        if (!hasField(target)) return List.of();
        return published.stream().filter(r -> r.aperture.target == target)
            .map(r -> new CarrierBounds(r.bounds.min, r.bounds.max)).toList();
    }
    private static void update() {
        long start = System.nanoTime(); tick++; rebuilt = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !ClientWorldLoader.getIsInitialized()) { clear(); return; }
            if (!IPGlobal.experimentalPortalColoredLighting || !available()) {
                publish(List.of()); ENTRIES.clear(); PortalColoredLightSampler.clear();
                reason = IPGlobal.experimentalPortalColoredLighting ? "Colorful Lighting unavailable" : "disabled";
                rebuild(); return;
            }
            if (mc.isPaused()) return;
            PortalColoredLightSampler.advance(tick);
            // Source snapshots are incremental. Five field publications/second bound native mesh rebuild work.
            if (tick % 4 == 0) updateFields();
            rebuild();
        } finally { lastUpdateNanos = System.nanoTime() - start; }
    }
    private static void updateFields() {
        var worlds = new ArrayList<>(ClientWorldLoader.getClientWorlds());
        var portals = new ArrayList<Portal>();
        for (ClientLevel world : worlds) for (var entity : world.entitiesForRendering()) {
            if (entity instanceof Portal p && !p.isRemoved() && !p.getIsGlobal()
                && p.getPortalShape() instanceof RectangularPortalShape && Math.abs(p.getScaling() - 1) < 1e-6
                && p.getWidth() > 0 && p.getHeight() > 0 && p.getWidth() <= 30 && p.getHeight() <= 30) portals.add(p);
        }
        portals.sort(Comparator.comparing(Portal::getUUID));
        var apertures = new LinkedHashSet<Aperture>();
        for (Portal p : portals) {
            ClientLevel here = (ClientLevel) p.level();
            ClientLevel there = worlds.stream().filter(w -> w.dimension().equals(p.dimensionTo)).findFirst().orElse(null);
            if (there == null) continue;
            for (boolean destination : new boolean[]{false, true}) {
                Pos inward = PortalLighting.receivingInward(p.getNormal(), p.getContentDirection(), destination);
                if (inward != null && apertures.size() < MAX_REGIONS) apertures.add(new Aperture(
                    destination ? there : here, destination ? here : there,
                    PortalLighting.apertureSamples(p.getWidth(), p.getHeight(), p.getNormal(),
                        p::getPointInPlane, p::transformPoint, destination), inward));
            }
        }
        ENTRIES.keySet().retainAll(apertures);
        var next = new ArrayList<Region>();
        String pending = "no supported loaded portal";
        for (Aperture a : apertures) {
            if (!shaderAllowed(a.target)) { pending = "receiving shader terrain RGB carrier unavailable"; continue; }
            var source = PortalColoredLightSampler.sample(a.source, a.samples.values().stream()
                .map(p -> new BlockPos(p.x(), p.y(), p.z())).toList(), tick);
            if (!source.supported() || !source.ready()) { pending = source.reason(); continue; }
            Entry e = ENTRIES.computeIfAbsent(a, ignored -> new Entry());
            int strength = (int) Math.ceil(Math.max(source.red(), Math.max(source.green(), source.blue())));
            var previous = e.snapshot;
            var update = PortalLightSnapshot.update(p -> cell(a.target, p, false, 0),
                p -> cell(a.source, p, true, strength), a.samples, a.inward, e.snapshot, e.dirty);
            e.dirty = false; e.snapshot = update.snapshot();
            if (!update.field().available()) { pending = update.field().reason(); continue; }
            if (e.region != null && previous != null && previous.field() == update.field()
                && e.red == source.red() && e.green == source.green() && e.blue == source.blue()) {
                next.add(e.region); continue;
            }
            var rgb = new HashMap<Pos, Integer>();
            for (var c : update.field().cells().entrySet()) {
                int distance = strength - c.getValue().block();
                rgb.put(c.getKey(), attenuated(source.red(), source.green(), source.blue(), distance));
            }
            e.red = source.red(); e.green = source.green(); e.blue = source.blue();
            e.region = new Region(a, Map.copyOf(rgb)); next.add(e.region);
        }
        boolean accepted = publish(List.copyOf(next));
        reason = !accepted ? "waiting for terrain rebuild budget" : next.isEmpty() ? pending : "active (one hop; native Colorful rendering)";
    }
    private static PortalLightSnapshot.Sample cell(ClientLevel world, Pos p, boolean source, int strength) {
        BlockPos b = new BlockPos(p.x(), p.y(), p.z());
        if (world.isOutsideBuildHeight(b) || !world.hasChunkAt(b)) return unavailableCell(source);
        var state = world.getBlockState(b);
        boolean open = source ? state.getLightBlock(world, b) < 15 : state.isAir();
        return new PortalLightSnapshot.Sample(open ? Cell.OPEN : Cell.CLOSED, new Light(0, source ? strength : 0));
    }
    static PortalLightSnapshot.Sample unavailableCell(boolean source) {
        // RGB is live native emission. Do not borrow the ambient/DH snapshot's retained bright source
        // when its chunk disappears; an unobserved aperture emits no synthetic color.
        return source ? new PortalLightSnapshot.Sample(Cell.CLOSED, new Light(0,0)) : PortalLightSnapshot.Sample.UNKNOWN;
    }
    static int attenuated(float red, float green, float blue, int distance) {
        return channel(red, distance) << 16 | channel(green, distance) << 8 | channel(blue, distance);
    }
    private static int channel(float value, int distance) {
        return !Float.isFinite(value) ? 0 : Math.clamp(Math.round((value - Math.max(0, distance)) * 17), 0, 255);
    }
    private static boolean publish(List<Region> next) {
        List<Region> old = published;
        if (old.equals(next)) return true;
        Set<DirtySection> changes = new HashSet<>();
        for (Region r : old) if (!next.contains(r)) queue(r, changes);
        for (Region r : next) if (!old.contains(r)) queue(r, changes);
        // Backpressure defers a new immutable field rather than dropping cleanup for old colored meshes.
        if (DIRTY.size() + changes.stream().filter(s -> !DIRTY.contains(s)).count() > MAX_DIRTY_SECTIONS) return false;
        // Publication precedes dirty notification: every new build sees one complete immutable field.
        published = next; revision++;
        DIRTY.addAll(changes); dirtyHighWater = Math.max(dirtyHighWater, DIRTY.size());
        return true;
    }
    private static void queue(Region region, Set<DirtySection> output) {
        // Sample interpolation reaches an adjacent block/section; include the one-cell border.
        for (Pos section : sections(region.rgb.keySet()))
            output.add(new DirtySection(region.aperture.target, section.x(), section.y(), section.z()));
    }
    static Set<Pos> sections(Set<Pos> cells) {
        if (cells.isEmpty()) return Set.of();
        Bounds bounds = bounds(cells);
        Set<Pos> result = new HashSet<>();
        for (int x = (bounds.min.x()-1)>>4; x <= (bounds.max.x()+1)>>4; x++)
            for (int y = (bounds.min.y()-1)>>4; y <= (bounds.max.y()+1)>>4; y++)
                for (int z = (bounds.min.z()-1)>>4; z <= (bounds.max.z()+1)>>4; z++) result.add(new Pos(x,y,z));
        return Set.copyOf(result);
    }
    private static Bounds bounds(Set<Pos> cells) {
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (Pos p : cells) {
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
        }
        return new Bounds(new Pos(minX,minY,minZ), new Pos(maxX,maxY,maxZ));
    }
    private static void rebuild() {
        var worlds = ClientWorldLoader.getClientWorlds();
        var iterator = DIRTY.iterator();
        int processed = 0;
        while (iterator.hasNext() && processed++ < MAX_REBUILDS_PER_TICK) {
            DirtySection section = iterator.next(); iterator.remove();
            if (worlds.stream().noneMatch(w -> w == section.world)) continue;
            ClientWorldLoader.getWorldRenderer(section.world.dimension()).setSectionDirty(section.x, section.y, section.z);
            rebuilt++;
        }
    }
    /** Called only by the optional Colorful mixin, including Sodium's worker threads. */
    public static Object merge(Object level, double x, double y, double z, Object nativeColor) {
        if (!IPGlobal.experimentalPortalColoredLighting || renderFailed || published.isEmpty() || bridge == null) return nativeColor;
        try {
            int imported = 0;
            for (Region r : published) if (r.bounds.contains(x,y,z) && bridge.belongs(level, r.aperture.target))
                imported = max(imported, sample(r.rgb, x, y, z));
            if (imported == 0) return nativeColor;
            int local = nativeColor == null ? 0 : bridge.read(nativeColor);
            int combined = max(local, imported);
            return combined == local && nativeColor != null ? nativeColor : bridge.create(combined);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reason = "Colorful adapter unavailable: " + failure.getClass().getSimpleName();
            if (!renderFailed) {
                renderFailed = true;
                com.mojang.logging.LogUtils.getLogger().warn("Experimental portal RGB rendering disabled after adapter failure", failure);
            }
            return nativeColor;
        }
    }
    static int max(int a, int b) {
        return Math.max(a >>> 16 & 255, b >>> 16 & 255) << 16
            | Math.max(a >>> 8 & 255, b >>> 8 & 255) << 8 | Math.max(a & 255, b & 255);
    }
    static int sample(Map<Pos, Integer> cells, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return 0;
        // Match native Colorful's cell-centered sample coordinates. Require containing air membership.
        Pos containing = new Pos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
        if (!cells.containsKey(containing)) return 0;
        double cx = x - .5, cy = y - .5, cz = z - .5;
        int bx = (int)Math.floor(cx), by = (int)Math.floor(cy), bz = (int)Math.floor(cz);
        double fx = cx-bx, fy = cy-by, fz = cz-bz;
        double red = 0, green = 0, blue = 0, weight = 0;
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
            Integer rgb = cells.get(new Pos(bx+dx, by+dy, bz+dz));
            if (rgb == null) continue;
            double w = (dx == 0 ? 1-fx : fx) * (dy == 0 ? 1-fy : fy) * (dz == 0 ? 1-fz : fz);
            red += (rgb >>> 16 & 255) * w; green += (rgb >>> 8 & 255) * w; blue += (rgb & 255) * w; weight += w;
        }
        if (weight <= 0) return 0;
        return (int)Math.round(red / weight) << 16 | (int)Math.round(green / weight) << 8 | (int)Math.round(blue / weight);
    }
    private static boolean available() {
        if (renderFailed) return false;
        if (!bridgeTried) {
            bridgeTried = true;
            var modList = net.neoforged.fml.ModList.get();
            String version = modList == null ? null : modList.getModContainerById("colorful_lighting")
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
            // Publishing while our optional hook is absent would remove scalar light without replacing it.
            if (!PortalColoredLightCompatibility.supports(version)) return false;
            try { bridge = new Bridge(); }
            catch (ReflectiveOperationException | LinkageError absent) { bridge = null; }
        }
        if (bridge == null) return false;
        try { return (boolean) bridge.enabled.invoke(null); }
        catch (ReflectiveOperationException failure) { return false; }
    }
    private static final class Bridge {
        final Method enabled, belongsTo, factory;
        final java.lang.reflect.Field red, green, blue;
        Bridge() throws ReflectiveOperationException {
            ClassLoader loader = PortalColoredLighting.class.getClassLoader();
            enabled = Class.forName("dev.colorfullighting.compat.CompatGates", false, loader).getMethod("engineEnabled");
            belongsTo = Class.forName("dev.colorfullighting.compat.level.RenderLevelScope", false, loader)
                .getMethod("belongsTo", Object.class, Object.class);
            Class<?> color = Class.forName("me.erykczy.colorfullighting.common.util.ColorRGB8", false, loader);
            factory = color.getMethod("fromRGB8", int.class, int.class, int.class);
            red = color.getField("red"); green = color.getField("green"); blue = color.getField("blue");
        }
        boolean belongs(Object level, ClientLevel world) throws ReflectiveOperationException { return (boolean)belongsTo.invoke(null, level, world); }
        int read(Object c) throws IllegalAccessException { return red.getInt(c) << 16 | green.getInt(c) << 8 | blue.getInt(c); }
        Object create(int rgb) throws ReflectiveOperationException { return factory.invoke(null, rgb >>> 16 & 255, rgb >>> 8 & 255, rgb & 255); }
    }
}
