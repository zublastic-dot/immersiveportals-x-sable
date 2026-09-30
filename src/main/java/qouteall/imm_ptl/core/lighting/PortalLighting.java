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
    public record Region(ClientLevel world, Portal portal, Pos min, Map<Pos,float[]> gains, int tick) {}
    private static final Map<ClientLevel,PortalLightPalette> PALETTES=new WeakHashMap<>();
    private static final Map<Portal,Region> REGIONS=new IdentityHashMap<>();
    private static final Map<Portal,String> REASONS=new WeakHashMap<>();
    private static int tick, cursor;
    private static long revision;
    // Deliberate escape hatch for the owner trial; does not change saved worlds/configs.
    public static final boolean ENABLED=!Boolean.getBoolean("imm_ptl.disablePortalLightTransport");
    private PortalLighting() {}
    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class,event -> update());
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class,event -> {
            REGIONS.clear(); PALETTES.clear(); REASONS.clear(); revision++; PortalLightGpu.clear();
        });
    }
    public static void capture(ClientLevel level,int[] pixels) {
        if (level!=null) PALETTES.put(level,new PortalLightPalette(pixels));
    }
    public static long revision() { return revision; }
    public static List<Region> regions(ClientLevel level) {
        if (!ENABLED || IrisInterface.invoker.isShaders()) return List.of();
        return REGIONS.values().stream().filter(r -> r.world==level && tick-r.tick<=80 && !r.portal.isRemoved())
            .sorted(Comparator.comparing(r -> r.portal.getUUID())).limit(4).toList();
    }
    private static void update() {
        Minecraft mc=Minecraft.getInstance(); tick++;
        if (mc.level==null || !ClientWorldLoader.getIsInitialized()) {
            if (!REGIONS.isEmpty() || !PALETTES.isEmpty()) { REGIONS.clear(); PALETTES.clear(); REASONS.clear(); revision++; }
            return;
        }
        if (!ENABLED || IrisInterface.invoker.isShaders() || tick%5!=0) return;
        var worlds=new ArrayList<>(ClientWorldLoader.getClientWorlds());
        PortalLightGpu.retain(worlds);
        boolean removed=REGIONS.entrySet().removeIf(e -> e.getKey().isRemoved() || !worlds.contains(e.getValue().world) || tick-e.getValue().tick>80);
        if (removed) revision++;
        var candidates=new ArrayList<Portal>();
        for (var world:worlds) for (var entity:world.entitiesForRendering()) {
            if (entity instanceof Portal p && eligible(p)) candidates.add(p);
        }
        candidates.sort(Comparator.comparing(Portal::getUUID));
        if (candidates.isEmpty()) return;
        // Bounded work on the client thread; never query mutable worlds on a mesh worker.
        Portal portal=candidates.get(Math.floorMod(cursor++,candidates.size()));
        ClientLevel world=(ClientLevel)portal.level();
        ClientLevel remote=worlds.stream().filter(w -> w.dimension().equals(portal.dimensionTo)).findFirst().orElse(null);
        if (remote==null) { reject(portal,"destination not loaded"); return; }
        Map<Pos,Light> seeds=seeds(world,remote,portal);
        if(seeds==null) { reject(portal,"unloaded aperture sample"); return; }
        Result result=solve(pos -> cell(world,pos),seeds,4096,32);
        if (!result.enclosed()) { reject(portal,result.reason()); return; }
        refreshPalette(world); refreshPalette(remote);
        PortalLightPalette nativePalette=PALETTES.get(world), incoming=PALETTES.get(remote);
        if (nativePalette==null || incoming==null) { reject(portal,"lightmap unavailable"); return; }
        var gains=new HashMap<Pos,float[]>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        for (var entry:result.cells().entrySet()) {
            Pos p=entry.getKey(); BlockPos block=new BlockPos(p.x(),p.y(),p.z()); Light light=entry.getValue();
            gains.put(p,nativePalette.gain(incoming,world.getBrightness(LightLayer.SKY,block),world.getBrightness(LightLayer.BLOCK,block),light.sky(),light.block()));
            minX=Math.min(minX,p.x()); minY=Math.min(minY,p.y()); minZ=Math.min(minZ,p.z());
        }
        REGIONS.put(portal,new Region(world,portal,new Pos(minX,minY,minZ),Map.copyOf(gains),tick)); revision++;
        report(portal,"enclosed cells="+gains.size());
    }
    private static boolean eligible(Portal p) {
        if (p.isRemoved() || p.getIsGlobal() || !(p.getPortalShape() instanceof RectangularPortalShape)
            || Math.abs(p.getScaling()-1)>1e-6 || p.getWidth()>30 || p.getHeight()>30) return false;
        Vec3 n=p.getNormal();
        return Math.max(Math.abs(n.x),Math.max(Math.abs(n.y),Math.abs(n.z)))>0.999999;
    }
    private static Map<Pos,Light> seeds(ClientLevel world,ClientLevel remote,Portal p) {
        var seeds=new HashMap<Pos,Light>(); Vec3 n=p.getNormal();
        for (double v=-p.getHeight()/2+.5;v<p.getHeight()/2;v++) for (double u=-p.getWidth()/2+.5;u<p.getWidth()/2;u++) {
            Vec3 plane=p.getPointInPlane(u,v);
            BlockPos here=BlockPos.containing(plane.add(n));
            BlockPos there=BlockPos.containing(p.transformPoint(plane.subtract(n)));
            Pos pos=new Pos(here.getX(),here.getY(),here.getZ());
            // A missing or blocked sample must not turn into a made-up bright source.
            if (!remote.hasChunkAt(there)) return null;
            if (remote.getBlockState(there).getLightBlock(remote,there)>=15) continue;
            Light light=new Light(remote.getBrightness(LightLayer.SKY,there)-1,remote.getBrightness(LightLayer.BLOCK,there)-1);
            seeds.merge(pos,light,Light::max);
        }
        return seeds;
    }
    private static Cell cell(ClientLevel world,Pos pos) {
        BlockPos block=new BlockPos(pos.x(),pos.y(),pos.z());
        if (world.isOutsideBuildHeight(block) || !world.hasChunkAt(block)) return Cell.UNKNOWN;
        // Conservative first adapter: partial solids/glass are boundaries, never an
        // excuse to illuminate the wrong side of a wall. Only actual air propagates.
        return world.getBlockState(block).isAir() ? Cell.OPEN : Cell.CLOSED;
    }
    private static void refreshPalette(ClientLevel level) {
        Minecraft mc=Minecraft.getInstance(); ClientLevel old=mc.level;
        // Resolve before changing level: helper construction decides whether to reuse
        // the main lightmap by comparing its world with Minecraft.level.
        var texture=ClientWorldLoader.getDimensionRenderHelper(level.dimension()).lightmapTexture;
        try { mc.level=level; texture.updateLightTexture(0); }
        finally { mc.level=old; }
    }
    private static void reject(Portal p,String reason) { if (REGIONS.remove(p)!=null) revision++; report(p,reason); }
    private static void report(Portal p,String reason) {
        if (!reason.equals(REASONS.put(p,reason))) LogUtils.getLogger().info("[IP portal light] {} {}: {}",p.level().dimension().location(),p.getUUID(),reason);
    }
}
