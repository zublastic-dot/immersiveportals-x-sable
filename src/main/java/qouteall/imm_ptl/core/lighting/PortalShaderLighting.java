package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.util.*;
import java.util.function.UnaryOperator;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Geometry/scalar transport for shader packs. Never publishes vanilla RGB deltas or writes light storage. */
public final class PortalShaderLighting {
    public static final int EDGE=32, SHADOW_EDGE=32, LIMIT=4;
    public record Aperture(ClientLevel target, ClientLevel source, Vec3 center, Vec3 inward,
                           Vec3 u, Vec3 v, double width, double height,
                           Vec3 sourceCenter, Vec3 sourceNormal, Vec3 sourceU, Vec3 sourceV,
                           Vec3 toSourceX, Vec3 toSourceY, Vec3 toSourceZ,
                           UnaryOperator<Vec3> toTargetDirection, Map<Pos,Pos> seeds) {}
    public record Region(Aperture aperture, Pos min, float[] cells, byte[] sourceShadow,
                         Vec3 direction, float sourceSunAngle, long revision,
                         Pos ambientMin, Pos ambientMax) {
        public Region(Aperture aperture, Pos min, float[] cells, byte[] sourceShadow,
                      Vec3 direction, float sourceSunAngle, long revision) {
            this(aperture,min,cells,sourceShadow,direction,sourceSunAngle,revision,min,min.add(EDGE-1,EDGE-1,EDGE-1));
        }
    }
    private record Key(ClientLevel level, Vec3 center, Vec3 inward, Vec3 u, Vec3 v, double width, double height,
                       ClientLevel source,Vec3 sourceCenter,Vec3 sourceNormal,Vec3 sourceU,Vec3 sourceV) {}
    private static final class Entry {
        PortalLightSnapshot.Snapshot snapshot;
        Region region;
        Map<Pos,Cell> apertureLayer=Map.of();
        boolean dirty=true, shadowDirty=true;
        Vec3 shadowDirection=Vec3.ZERO;
        int shadowTick=-100, retry;
        final PortalSunOcclusion sourceGeometry;
        boolean shadowLogged,litShadowLogged;
        Entry(ClientLevel source) { sourceGeometry=new PortalSunOcclusion(source,PortalSunOcclusion.DEFAULT_SECTIONS); }
    }
    private static final Map<Key,Entry> ENTRIES=new LinkedHashMap<>();
    private static int tick;
    private static long revision;
    private static boolean initialized;
    private static int shadowDiagnostics;
    private PortalShaderLighting() {}
    public static void init() {
        if(initialized) return;
        initialized=true;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,event->update());
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class,event->{
            if(event.getLevel() instanceof ClientLevel w) {
                var chunk=event.getChunk().getPos();
                chunkChanged(w,chunk.x,chunk.z,true);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class,event->{
            if(event.getLevel() instanceof ClientLevel w) {
                var chunk=event.getChunk().getPos();
                chunkChanged(w,chunk.x,chunk.z,false);
            }
        });
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class,event->{
            ENTRIES.clear();shadowDiagnostics=0;PortalShaderGpu.clear();
        });
    }
    private static void chunkChanged(ClientLevel world,int chunkX,int chunkZ,boolean loaded) {
        ENTRIES.forEach((key,e)->{
            if(key.level==world) e.dirty=true;
            // Retain source observations on unload. Newly received chunk data supersedes them.
            if(loaded && key.source==world) {
                boolean observed=e.sourceGeometry.invalidateChunk(chunkX,chunkZ);
                if(observed || e.sourceGeometry.evictions()>0) e.shadowDirty=true;
            }
        });
    }
    public static void blockChanged(ClientLevel world,BlockPos pos) {
        Pos changed=new Pos(pos.getX(),pos.getY(),pos.getZ());
        ENTRIES.forEach((key,e)->{
            if(key.level==world && (e.snapshot==null || e.snapshot.geometry().containsKey(changed))) e.dirty=true;
            if(key.source==world) {
                boolean observed=e.sourceGeometry.invalidate(changed);
                // A published mask may include an observation since evicted by the LRU.
                // Its mutation must still invalidate that mask at a stopped sun clock.
                if(observed || e.sourceGeometry.evictions()>0) e.shadowDirty=true;
            }
        });
    }
    public static List<Region> regions(ClientLevel world) {
        if(!supported()) return List.of();
        return ENTRIES.values().stream().map(e->e.region).filter(Objects::nonNull)
            .filter(r->r.aperture.target==world).limit(LIMIT).toList();
    }
    public static boolean supported() {
        String pack=IrisInterface.invoker.getShaderpackName();
        return PortalLighting.ENABLED && IrisInterface.invoker.isShaders() && pack!=null
            && PortalShaderPackAdapter.supports(pack);
    }
    private static void update() {
        Minecraft mc=Minecraft.getInstance(); tick++;
        if(mc.level==null || !supported()) { ENTRIES.clear(); return; }
        if(mc.isPaused() || !ClientWorldLoader.getIsInitialized()) return;
        List<ClientLevel> worlds=new ArrayList<>(ClientWorldLoader.getClientWorlds());
        PortalShaderGpu.retain(worlds);
        Map<Key,Aperture> apertures=new LinkedHashMap<>();
        List<Portal> portals=new ArrayList<>();
        for(var world:worlds) for(var entity:world.entitiesForRendering())
            if(entity instanceof Portal p && !p.isRemoved() && !p.getIsGlobal()
                && p.getPortalShape() instanceof RectangularPortalShape && Math.abs(p.getScaling()-1)<1e-6
                && p.getWidth()>0 && p.getHeight()>0 && p.getWidth()<=30 && p.getHeight()<=30) portals.add(p);
        portals.sort(Comparator.comparing(Portal::getUUID));
        for(Portal p:portals) {
            ClientLevel other=worlds.stream().filter(w->w.dimension().equals(p.dimensionTo)).findFirst().orElse(null);
            if(other==null) continue;
            boolean dest=p.level().dimension().equals(Level.OVERWORLD) && other.dimension().equals(Level.NETHER);
            if(!dest && !(p.level().dimension().equals(Level.NETHER) && other.dimension().equals(Level.OVERWORLD))) continue;
            Vec3 normal=dest?p.getContentDirection():p.getNormal();
            Pos inward=PortalLighting.receivingInward(p.getNormal(),p.getContentDirection(),dest);
            if(inward==null) continue;
            UnaryOperator<Vec3> toTarget=dest?p::transformLocalVecNonScale:p::inverseTransformLocalVecNonScale;
            UnaryOperator<Vec3> toSource=dest?p::inverseTransformLocalVecNonScale:p::transformLocalVecNonScale;
            Aperture a=new Aperture(dest?other:(ClientLevel)p.level(),dest?(ClientLevel)p.level():other,
                dest?p.getDestPos():p.getOriginPos(),normal,
                dest?p.transformLocalVecNonScale(p.getAxisW()):p.getAxisW(),
                dest?p.transformLocalVecNonScale(p.getAxisH()):p.getAxisH(),p.getWidth(),p.getHeight(),
                dest?p.getOriginPos():p.getDestPos(),dest?p.getNormal():p.getContentDirection(),
                dest?p.getAxisW():p.transformLocalVecNonScale(p.getAxisW()),
                dest?p.getAxisH():p.transformLocalVecNonScale(p.getAxisH()),
                toSource.apply(new Vec3(1,0,0)),toSource.apply(new Vec3(0,1,0)),toSource.apply(new Vec3(0,0,1)),toTarget,
                PortalLighting.apertureSamples(p.getWidth(),p.getHeight(),p.getNormal(),p::getPointInPlane,p::transformPoint,dest));
            Key key=new Key(a.target,a.center,a.inward,a.u,a.v,a.width,a.height,
                a.source,a.sourceCenter,a.sourceNormal,a.sourceU,a.sourceV);
            if(apertures.size()<LIMIT) apertures.putIfAbsent(key,a);
        }
        ENTRIES.keySet().retainAll(apertures.keySet());
        var lightSamples=PortalLightSamples.pass();
        for(var item:apertures.entrySet()) {
            Aperture a=item.getValue(); Entry e=ENTRIES.computeIfAbsent(item.getKey(),k->new Entry(a.source));
            if(e.snapshot==null && tick<e.retry) continue;
            var old=e.snapshot;
            Pos inward=new Pos((int)Math.round(a.inward.x),(int)Math.round(a.inward.y),(int)Math.round(a.inward.z));
            boolean geometryDirty=e.dirty;
            var result=PortalLightSnapshot.update(p->sample(a.target,p,false,lightSamples),p->sample(a.source,p,true,lightSamples),a.seeds,inward,old,geometryDirty);
            e.dirty=false;
            if(!result.field().available()) { e.snapshot=null;e.region=null;e.retry=tick+10;continue; }
            e.snapshot=result.snapshot();
            var oldLayer=e.apertureLayer;
            if(old==null || geometryDirty) e.apertureLayer=observeApertureLayer(a,e.snapshot,
                p->occupancy(a.target,p,inward),oldLayer);
            boolean occupancyChanged=!oldLayer.equals(e.apertureLayer);
            float angle=sunAngle(a.source.getTimeOfDay(0));
            var rotation=PortalShaderPackAdapter.sunPathRotationDegrees();
            var clock=PortalShaderPackAdapter.clockMode();
            Vec3 sun=rotation.isPresent() && clock.isPresent()
                ?sourceDirection(angle,a.source.getDayTime(),rotation.getAsDouble(),clock.get()):Vec3.ZERO;
            boolean shadowChanged=e.region==null || e.shadowDirty ||
                (tick-e.shadowTick>=5 && sun.distanceToSqr(e.shadowDirection)>1e-8);
            byte[] shadow=e.region==null?new byte[SHADOW_EDGE*SHADOW_EDGE]:e.region.sourceShadow;
            if(shadowChanged) {
                shadow=sourceShadow(a,sun,e.sourceGeometry);e.shadowTick=tick;e.shadowDirection=sun;e.shadowDirty=false;
                int lit=0;for(byte value:shadow) if(value!=0) lit++;
                if(rotation.isPresent() && clock.isPresent() && shadowDiagnostics<64
                    && (!e.shadowLogged || (lit>0 && !e.litShadowLogged))) {
                    shadowDiagnostics++;e.shadowLogged=true;e.litShadowLogged|=lit>0;
                    LogUtils.getLogger().info("[IP shader light] source shadow {}/{} lit texels at angle {}; {} cached sections, {} world reads, {} evictions",
                        lit,shadow.length,angle,e.sourceGeometry.sectionCount(),e.sourceGeometry.worldReads(),e.sourceGeometry.evictions());
                }
            }
            boolean geometryChanged=old==null || old.topology()!=e.snapshot.topology();
            boolean valuesChanged=old==null || old.field()!=e.snapshot.field();
            if(e.region==null || geometryChanged || valuesChanged || occupancyChanged || shadowChanged) {
                Bounds ambient=bounds(e.snapshot.field().cells().keySet());
                Pos min=atlasMin(e.snapshot,e.apertureLayer);
                float[] cells=e.region!=null && !geometryChanged && !valuesChanged && !occupancyChanged
                    ?e.region.cells:packCells(e.snapshot,min,e.apertureLayer);
                e.region=new Region(a,min,cells,shadow,a.toTargetDirection.apply(sun),angle,++revision,ambient.min,ambient.max);
                if(old==null) LogUtils.getLogger().info("[IP shader light] admitted {} receiving cells in {} from {}; sunlight parameters {}",
                    e.snapshot.field().cells().size(),a.target.dimension().location(),a.source.dimension().location(),rotation.isPresent()?"observed":"pending source shader");
            }
        }
    }
    private record Bounds(Pos min,Pos max) {}
    private static Bounds bounds(Collection<Pos> positions) {
        Pos min=new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        Pos max=new Pos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE);
        for(Pos p:positions) {
            min=new Pos(Math.min(min.x(),p.x()),Math.min(min.y(),p.y()),Math.min(min.z(),p.z()));
            max=new Pos(Math.max(max.x(),p.x()),Math.max(max.y(),p.y()),Math.max(max.z(),p.z()));
        }
        return new Bounds(min,max);
    }
    /**
     * Seeds lie one block inside the portal. Observe the omitted plane layer explicitly:
     * its matching portal placeholders are ray occupancy, never ambient-light seeds.
     * The snapshot's dense one-cell halo supplies the bounded candidate set and dirties
     * this layer on real block/chunk updates, including non-air -> non-air changes.
     */
    static Map<Pos,Cell> observeApertureLayer(Aperture a,PortalLightSnapshot.Snapshot snapshot,
                                           World reader,Map<Pos,Cell> previous) {
        Pos inward=snapshot.inward();int axis=inward.x()!=0?0:inward.y()!=0?1:2;
        int plane=snapshot.aperture().keySet().iterator().next().component(axis)-inward.component(axis);
        Bounds ambient=bounds(snapshot.field().cells().keySet());
        var observed=new HashMap<Pos,Cell>();
        for(Pos p:snapshot.geometry().keySet()) {
            if(p.component(axis)!=plane) continue;
            boolean outside=false;
            for(int d=0;d<3;d++) if(d!=axis && (p.component(d)<ambient.min.component(d)
                || p.component(d)>ambient.max.component(d))) outside=true;
            if(outside) continue;
            Vec3 offset=new Vec3(p.x()+.5,p.y()+.5,p.z()+.5).subtract(a.center);
            double uRadius=(Math.abs(a.u.x)+Math.abs(a.u.y)+Math.abs(a.u.z))*.5;
            double vRadius=(Math.abs(a.v.x)+Math.abs(a.v.y)+Math.abs(a.v.z))*.5;
            if(Math.abs(offset.dot(a.u))>=a.width*.5+uRadius
                || Math.abs(offset.dot(a.v))>=a.height*.5+vRadius) continue;
            Cell cell=reader.cell(p);
            if(cell==null || cell==Cell.UNKNOWN) cell=previous.getOrDefault(p,Cell.UNKNOWN);
            observed.put(p,cell);
        }
        return Map.copyOf(observed);
    }
    static Pos atlasMin(PortalLightSnapshot.Snapshot snapshot,Map<Pos,Cell> apertureLayer) {
        Pos a=bounds(snapshot.field().cells().keySet()).min;
        if(apertureLayer.isEmpty()) return a;
        Pos b=bounds(apertureLayer.keySet()).min;
        return new Pos(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()));
    }
    private static int cellIndex(Pos p,Pos min) {
        int x=p.x()-min.x(),y=p.y()-min.y(),z=p.z()-min.z();
        if(x<0||y<0||z<0||x>=EDGE||y>=EDGE||z>=EDGE) throw new IllegalArgumentException("Unbounded shader field");
        return ((z*EDGE+y)*EDGE+x)*4;
    }
    static float[] packCells(PortalLightSnapshot.Snapshot snapshot,Pos min,Map<Pos,Cell> apertureLayer) {
        float[] cells=new float[EDGE*EDGE*EDGE*4];
        for(var cell:apertureLayer.entrySet()) {
            int i=cellIndex(cell.getKey(),min);
            if(cell.getValue()==Cell.OPEN) cells[i+3]=1;
        }
        for(var cell:snapshot.field().cells().entrySet()) {
            Pos p=cell.getKey();int i=cellIndex(p,min);
            cells[i]=cell.getValue().sky()/15f;cells[i+1]=cell.getValue().block()/15f;
            cells[i+2]=snapshot.field().replacement().get(p);cells[i+3]=1;
        }
        return cells;
    }
    private static Cell occupancy(ClientLevel w,Pos p,Pos inward) {
        BlockPos b=new BlockPos(p.x(),p.y(),p.z());
        if(w.isOutsideBuildHeight(b)||!w.hasChunkAt(b)) return Cell.UNKNOWN;
        return apertureOccupancy(w.getBlockState(b),w,b,inward);
    }
    /** Only called for observed cells in this validated aperture's bounded plane layer. */
    static Cell apertureOccupancy(BlockState state,BlockGetter world,BlockPos pos,Pos inward) {
        if(state==null) return Cell.UNKNOWN;
        if(state.getBlock() instanceof PortalPlaceholderBlock) {
            // Vanilla opacity stays 15 to block light from the hidden local continuation.
            // The matching aperture surface instead admits this explicit cross-world ray.
            if(Math.abs(inward.x())+Math.abs(inward.y())+Math.abs(inward.z())!=1) return Cell.CLOSED;
            Direction.Axis axis=inward.x()!=0?Direction.Axis.X:inward.y()!=0?Direction.Axis.Y:Direction.Axis.Z;
            return state.getValue(PortalPlaceholderBlock.AXIS)==axis?Cell.OPEN:Cell.CLOSED;
        }
        return state.getLightBlock(world,pos)>=15?Cell.CLOSED:Cell.OPEN;
    }
    private static byte[] sourceShadow(Aperture a,Vec3 sun,PortalSunOcclusion cache) {
        World reader=p->{
            BlockPos b=new BlockPos(p.x(),p.y(),p.z());
            if(p.y()>=a.source.getMaxBuildHeight()) return Cell.OPEN;
            if(a.source.isOutsideBuildHeight(b)||!a.source.hasChunkAt(b)) return Cell.UNKNOWN;
            return a.source.getBlockState(b).getLightBlock(a.source,b)>=15?Cell.CLOSED:Cell.OPEN;
        };
        return cache.mask(a.source,reader,a.sourceCenter,a.sourceNormal,a.sourceU,a.sourceV,
            a.width,a.height,sun,a.source.getMaxBuildHeight(),SHADOW_EDGE);
    }
    private static PortalLightSnapshot.Sample sample(ClientLevel w,Pos p,boolean source,PortalLightSamples.Pass<ClientLevel> lightSamples) {
        BlockPos b=new BlockPos(p.x(),p.y(),p.z());
        if(w.isOutsideBuildHeight(b)||!w.hasChunkAt(b)) return PortalLightSnapshot.Sample.UNKNOWN;
        var state=w.getBlockState(b);
        boolean open=source?state.getLightBlock(w,b)<15:state.isAir();
        return new PortalLightSnapshot.Sample(open?Cell.OPEN:Cell.CLOSED,
            new Light(w.getBrightness(LightLayer.SKY,b),lightSamples.block(w,b)));
    }
    static float sunAngle(float skyAngle) { return skyAngle<.75f?skyAngle+.25f:skyAngle-.75f; }
    static double packTimeAngle(double sunAngle) {
        double a=sunAngle-.033333333;a-=Math.floor(a);
        double linear=a<.433333333?a*1.15384615385:a*.882352941176+.117647058824;
        double half=linear>.5?1:0,f=(linear*2)%1,s=f*f*(3-2*f),m=half<.5?.3:-.1;
        return (f*(1-m)+s*m+half)*.5;
    }
    static Vec3 sourceDirection(double sunAngle,double pathRotation) {
        return sourceDirection(sunAngle,0,pathRotation,PortalShaderPackAdapter.ClockMode.SUN_ANGLE);
    }
    static Vec3 sourceDirection(double sunAngle,long worldTime,double pathRotation,PortalShaderPackAdapter.ClockMode clock) {
        double time=clock==PortalShaderPackAdapter.ClockMode.WORLD_TIME
            ?Math.floorMod(worldTime,24000L)/24000.0:packTimeAngle(sunAngle);
        double raw=time-.25;raw-=Math.floor(raw);
        double angle=(raw+(Math.cos(raw*Math.PI)*-.5+.5-raw)/3)*Math.PI*2;
        double r=Math.toRadians(pathRotation);
        Vec3 sun=new Vec3(-Math.sin(angle),Math.cos(angle)*Math.cos(r),-Math.cos(angle)*Math.sin(r));
        return time>.5325 && time<.9675?sun.scale(-1):sun;
    }
}
