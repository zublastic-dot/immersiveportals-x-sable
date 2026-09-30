package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.util.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Client-only trial. No light storage writes, chunk loads, server hooks or room coordinates. */
public final class PortalLighting {
    public record Region(ClientLevel world, Pos min, Map<Pos, float[]> gains) {}
    // Value identity survives unloading/recreation of the destination portal entity.
    // The sample mapping also changes when either endpoint moves or is retargeted.
    private record Aperture(ClientLevel world, ClientLevel source, Map<Pos, Pos> samples) {}
    private static final Map<ClientLevel, PortalLightPalette> PALETTES = new WeakHashMap<>();
    private static final Map<Aperture, Region> REGIONS = new HashMap<>();
    private static final Map<Aperture, PortalLightSnapshot.Snapshot> SNAPSHOTS = new HashMap<>();
    private static final Map<Aperture, String> REASONS = new HashMap<>();
    private static int tick, cursor;
    private static long revision;
    public static final boolean ENABLED = !Boolean.getBoolean("imm_ptl.disablePortalLightTransport");
    private PortalLighting() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, event -> update());
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class, event -> {
            clear(); PortalLightGpu.clear();
        });
    }
    private static void clear() {
        REGIONS.clear(); SNAPSHOTS.clear(); PALETTES.clear(); REASONS.clear(); revision++;
    }
    public static void capture(ClientLevel level, int[] pixels) {
        if (level != null) PALETTES.put(level, new PortalLightPalette(pixels));
    }
    public static long revision() { return revision; }
    public static List<Region> regions(ClientLevel level) {
        if (!ENABLED || IrisInterface.invoker.isShaders()) return List.of();
        return REGIONS.values().stream().filter(r -> r.world == level)
            .sorted(Comparator.comparingInt((Region r) -> r.min.x())
                .thenComparingInt(r -> r.min.y()).thenComparingInt(r -> r.min.z())).limit(4).toList();
    }

    private static void update() {
        Minecraft mc = Minecraft.getInstance(); tick++;
        if (mc.level == null || !ClientWorldLoader.getIsInitialized()) {
            if (!REGIONS.isEmpty() || !PALETTES.isEmpty()) clear();
            return;
        }
        if (!ENABLED || IrisInterface.invoker.isShaders() || tick % 5 != 0) return;
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
            // Either endpoint can keep both views alive. DH may still draw an enclosure
            // after vanilla has unloaded its chunks and the portal entity inside them.
            if (apertures.size() < 16) apertures.add(aperture(world, remote, p, false));
            if (apertures.size() < 16) apertures.add(aperture(remote, world, p, true));
        }
        boolean removed = REGIONS.keySet().removeIf(a -> !apertures.contains(a));
        SNAPSHOTS.keySet().retainAll(apertures); REASONS.keySet().retainAll(apertures);
        if (removed) revision++;
        if (apertures.isEmpty()) return;
        Aperture a = new ArrayList<>(apertures).get(Math.floorMod(cursor++, apertures.size()));
        var result = PortalLightSnapshot.update(p -> sample(a.world, p, false),
            p -> sample(a.source, p, true), a.samples, SNAPSHOTS.get(a));
        if (!result.field().enclosed()) {
            SNAPSHOTS.remove(a);
            if (REGIONS.remove(a) != null) revision++;
            report(a, result.field().reason()); return;
        }
        SNAPSHOTS.put(a, result.snapshot());
        refreshPalette(a.world); refreshPalette(a.source);
        PortalLightPalette nativePalette = PALETTES.get(a.world), incoming = PALETTES.get(a.source);
        if (nativePalette == null || incoming == null) {
            if (REGIONS.remove(a) != null) revision++;
            report(a, "lightmap unavailable"); return;
        }
        var gains = new HashMap<Pos, float[]>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (var entry : result.field().cells().entrySet()) {
            Pos p = entry.getKey(); Light light = entry.getValue();
            Light local = result.snapshot().geometry().get(p).light();
            gains.put(p, nativePalette.gain(incoming, local.sky(), local.block(), light.sky(), light.block()));
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
        }
        REGIONS.put(a, new Region(a.world, new Pos(minX, minY, minZ), Map.copyOf(gains))); revision++;
        report(a, "enclosed cells=" + gains.size() + (result.usedCache() ? " (cached geometry/light levels; current lightmaps)" : " (live)"));
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
        return new Aperture(world, source, Map.copyOf(samples));
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
