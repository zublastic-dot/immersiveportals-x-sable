package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

/** Native colored block light rendered in remote portal views, independent of experimental light transport. */
public final class PortalNativeColoredLighting {
    private static final PortalNativeColorCache<ClientLevel> CACHE =
        new PortalNativeColorCache<>(PortalColoredLightAdapter::reader, System::nanoTime);
    private static volatile Api api;
    private static volatile boolean active, failed;
    private static final AtomicBoolean CONFIG_CHANGED = new AtomicBoolean();
    private static final PortalPrimaryColorContext<ClientLevel,Object> PRIMARY = new PortalPrimaryColorContext<>();
    private static final PortalColorWarmup<ClientLevel> WARMUP = new PortalColorWarmup<>();
    private static volatile ClientLevel primaryWorld;
    private static LevelRenderer primaryRenderer;
    private static boolean initialized, checked;
    private static long tick;
    private static String state = "not initialized";
    private PortalNativeColoredLighting() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, event -> publishPrimary());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> update());
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ClientLevel world) {
                var pos=event.getChunk().getPos(); CACHE.invalidateChunk(world,pos.x,pos.z);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ClientLevel world) {
                var pos=event.getChunk().getPos(); CACHE.invalidateChunk(world,pos.x,pos.z);
            }
        });
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class, event -> clear());
    }
    public static void blockChanged(ClientLevel world, BlockPos pos) {
        CACHE.invalidate(world,new Pos(pos.getX(),pos.getY(),pos.getZ()));
    }
    public static void clear() {
        active=false; PRIMARY.clear(); WARMUP.clear(); primaryWorld=null; primaryRenderer=null; CACHE.clear(); state="no client world";
    }
    /** Config setters may run on a resource-reload thread; only the next client tick touches cache state. */
    public static void configChanged() { CONFIG_CHANGED.set(true); }

    /** Before native Colorful's Post tick, pin its singleton producer to the actual player world. */
    private static void publishPrimary() {
        var minecraft=Minecraft.getInstance();
        if (minecraft.player==null || !ClientWorldLoader.getIsInitialized()) {
            PRIMARY.clear(); WARMUP.clear(); primaryWorld=null; primaryRenderer=null; return;
        }
        if (!initializeApi()) return;
        var world=(ClientLevel)minecraft.player.level();
        var renderer=ClientWorldLoader.getWorldRenderer(world.dimension());
        if (world==primaryWorld && renderer==primaryRenderer) return;
        try {
            Object accessor=api.level.invokeExact(world,renderer);
            if (primaryWorld!=null && primaryWorld!=world) {
                // IP retains both ClientLevels on an immersive crossing, so native LevelEvent.Unload
                // is not guaranteed. Colorful's storage/view area is coordinate-only: equal X/Z in
                // another dimension must not reuse it. Stop/join the old native producer first.
                PRIMARY.clear();
                Object engine=api.engine.invokeExact();
                if (engine!=null) {
                    api.reset.invokeExact(engine);
                    // reset's callback still sees the previous primary; arm the new exact identity
                    // before publishing it and before native Post tick begins destination propagation.
                    WARMUP.begin(world,engine);
                }
            }
            PRIMARY.publish(world,accessor); primaryWorld=world; primaryRenderer=renderer;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            failed=true; PRIMARY.clear();
            com.mojang.logging.LogUtils.getLogger().warn("Primary Colorful world adapter unavailable",failure);
        }
    }
    /** No world reads: a teleport mismatch yields null until Pre tick publishes the replacement accessor. */
    public static Object primaryAccessor(Minecraft minecraft,Object original) {
        if (api==null || failed) return original;
        ClientLevel actual=minecraft.player==null ? null : (ClientLevel)minecraft.player.level();
        return PRIMARY.select(actual);
    }
    /** Identity/generation guarded lifecycle callbacks, including resets outside IP crossings. */
    public static void nativeReset(Object engine) { WARMUP.begin(primaryWorld,engine); }
    public static long nativeWarmupGeneration(Object engine) { return WARMUP.token(primaryWorld,engine); }
    public static void nativeInitialLightReady(Object engine,long generation) { WARMUP.complete(primaryWorld,engine,generation); }

    private static void update() {
        var minecraft=Minecraft.getInstance(); tick++;
        if (minecraft.player==null || !ClientWorldLoader.getIsInitialized()) { clear(); return; }
        if (!available()) {
            active=false; CACHE.worlds(List.of()); rebuild();
            state=failed ? "optional adapter failed" : PortalColoredLightAdapter.reason(); return;
        }
        // Minecraft.level is temporarily replaced by a portal render pass. Player.level is the stable owner.
        var primary=minecraft.player.level();
        var remote=ClientWorldLoader.getClientWorlds().stream().filter(world -> world!=primary).toList();
        CACHE.worlds(remote,List.copyOf(ClientWorldLoader.getClientWorlds())); active=true;
        if (CONFIG_CHANGED.getAndSet(false)) CACHE.invalidateAll();
        if (!minecraft.isPaused()) {
            var centers=new IdentityHashMap<ClientLevel,List<Pos>>();
            int centerCount=0;
            for (var world:ClientWorldLoader.getClientWorlds()) for (var entity:world.entitiesForRendering()) {
                if (centerCount<32 && entity instanceof Portal portal && !portal.isRemoved() && portal.getDestPos()!=null) {
                    for (ClientLevel destination:remote) if (destination.dimension().equals(portal.dimensionTo)) {
                        var pos=BlockPos.containing(portal.getDestPos());
                        centers.computeIfAbsent(destination,ignored -> new ArrayList<>()).add(new Pos(pos.getX(),pos.getY(),pos.getZ()));
                        centerCount++;
                    }
                }
            }
            ToDoubleFunction<PortalNativeColorCache.Section<ClientLevel>> priority=section -> centers.getOrDefault(section.world,List.of()).stream().mapToDouble(pos -> {
                double x=section.x*16.0+8-pos.x(), y=section.y*16.0+8-pos.y(), z=section.z*16.0+8-pos.z();
                return x*x+y*y+z*z;
            }).min().orElse(Double.MAX_VALUE);
            var seeds=new LinkedHashSet<PortalNativeColorCache.Section<ClientLevel>>();
            centers.forEach((world,positions) -> positions.forEach(pos -> {
                for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                    if (seeds.size()<PortalNativeColorCache.MAX_FIELDS)
                        seeds.add(new PortalNativeColorCache.Section<>(world,(pos.x()>>4)+dx,(pos.y()>>4)+dy,(pos.z()>>4)+dz));
                }
            }));
            seeds.stream().sorted(Comparator.comparingDouble(priority)).forEach(section -> CACHE.seed(section,priority));
            CACHE.advance(tick,priority);
            if (!PortalColoredLightAdapter.available()) {
                active=false; CACHE.worlds(List.of()); state=PortalColoredLightAdapter.reason();
            } else state="native destination block RGB";
        }
        rebuild();
    }
    private static void rebuild() {
        var worlds=ClientWorldLoader.getClientWorlds();
        CACHE.discardUnloadedWorlds(List.copyOf(worlds));
        CACHE.rebuild(section -> {
            if (worlds.stream().noneMatch(world -> world==section.world)) return true;
            return SodiumInterface.invoker.schedulePortalLightRebuild(
                ClientWorldLoader.getWorldRenderer(section.world.dimension()),section.x,section.y,section.z)
                != SodiumInterface.PortalLightRebuild.RETRY;
        });
    }
    /** Worker callback: no Minecraft singleton, world block reads, or mutable native engine are consulted. */
    public static Object sample(Object view,double x,double y,double z,Object original) {
        Api current=api;
        if (!active || current==null || failed) return original;
        try {
            Object root=current.viewType.isInstance(view) ? current.root.invokeExact(view) : view;
            if (!(root instanceof ClientLevel level)) return original;
            if (level==primaryWorld) {
                if (!WARMUP.applies(level)) return original;
                Integer retained=CACHE.sampleRetained(level,x,y,z);
                if (retained==null) return original;
                // Native trySampleTrilinear returns null before it samples dynamics when static
                // storage is cold. Preserve its primary-world held/entity lights explicitly here.
                Object dynamic=current.dynamic.invokeExact(x,y,z);
                int rgb=PortalColorWarmup.combine(retained,current.dynamicRgb(dynamic));
                return current.color.invokeExact(rgb>>>16 & 255,rgb>>>8 & 255,rgb & 255);
            }
            if (!CACHE.accepts(level)) return original;
            Integer color=CACHE.sample(level,x,y,z);
            // A pending remote field deliberately uses vanilla's scalar fallback, never another world's RGB.
            return color==null ? null : current.color.invokeExact(color>>>16 & 255,color>>>8 & 255,color & 255);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (!failed) {
                failed=true;
                com.mojang.logging.LogUtils.getLogger().warn("Remote portal native Colorful adapter disabled after failure",failure);
            }
            return original;
        }
    }
    private static boolean available() {
        return initializeApi() && PortalColoredLightAdapter.available();
    }
    private static boolean initializeApi() {
        if (failed) return false;
        if (!checked) {
            checked=true;
            var modList=net.neoforged.fml.ModList.get();
            String version=modList==null ? null : modList.getModContainerById("colorful_lighting")
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
            if (!PortalColoredLightCompatibility.supports(version)) return false;
            try { api=new Api(); }
            catch (ReflectiveOperationException | LinkageError failure) { failed=true; return false; }
        }
        return api!=null;
    }
    public static Map<String,Object> status() {
        return Map.ofEntries(Map.entry("active",active),Map.entry("state",state),
            Map.entry("primaryWarmup",WARMUP.applies(primaryWorld)),Map.entry("warmupGeneration",WARMUP.generation()),
            Map.entry("fields",CACHE.size()),Map.entry("ready",CACHE.ready()),Map.entry("pending",CACHE.pending()),
            Map.entry("completed",CACHE.completed),Map.entry("readsLastTick",CACHE.reads),Map.entry("stepsLastTick",CACHE.steps),
            Map.entry("nanosLastTick",CACHE.elapsed),Map.entry("maxNanosPerTick",PortalNativeColorCache.MAX_NANOS),
            Map.entry("maxFields",PortalNativeColorCache.MAX_FIELDS),Map.entry("pendingRebuilds",CACHE.pendingRebuilds()),
            Map.entry("rebuiltLastTick",CACHE.rebuilt),Map.entry("sources",CACHE.describe(world -> world.dimension().location().toString())));
    }
    private static final class Api {
        final Class<?> viewType;
        final MethodHandle root,color,level,engine,reset,dynamic,red4,green4,blue4;
        Api() throws ReflectiveOperationException {
            var lookup=MethodHandles.publicLookup(); var loader=PortalNativeColoredLighting.class.getClassLoader();
            viewType=Class.forName("dev.colorfullighting.compat.level.RenderLevelView",false,loader);
            root=lookup.unreflect(viewType.getMethod("colorfulLighting$getRootLevel"))
                .asType(MethodType.methodType(Object.class,Object.class));
            var rgb=Class.forName("me.erykczy.colorfullighting.common.util.ColorRGB8",false,loader);
            color=lookup.unreflect(rgb.getMethod("fromRGB8",int.class,int.class,int.class))
                .asType(MethodType.methodType(Object.class,int.class,int.class,int.class));
            var wrapper=Class.forName("me.erykczy.colorfullighting.accessors.LevelWrapper",false,loader);
            level=lookup.unreflectConstructor(wrapper.getConstructor(ClientLevel.class,LevelRenderer.class))
                .asType(MethodType.methodType(Object.class,ClientLevel.class,LevelRenderer.class));
            var engineType=Class.forName("me.erykczy.colorfullighting.common.ColoredLightEngine",false,loader);
            engine=lookup.unreflect(engineType.getMethod("getInstance")).asType(MethodType.methodType(Object.class));
            reset=lookup.unreflect(engineType.getMethod("reset")).asType(MethodType.methodType(void.class,Object.class));
            var dynamicType=Class.forName("me.erykczy.colorfullighting.common.EntityLightManager",false,loader);
            dynamic=lookup.unreflect(dynamicType.getMethod("sampleLightColor",double.class,double.class,double.class))
                .asType(MethodType.methodType(Object.class,double.class,double.class,double.class));
            var rgb4=Class.forName("me.erykczy.colorfullighting.common.util.ColorRGB4",false,loader);
            red4=lookup.unreflectGetter(rgb4.getField("red4")).asType(MethodType.methodType(int.class,Object.class));
            green4=lookup.unreflectGetter(rgb4.getField("green4")).asType(MethodType.methodType(int.class,Object.class));
            blue4=lookup.unreflectGetter(rgb4.getField("blue4")).asType(MethodType.methodType(int.class,Object.class));
        }
        int dynamicRgb(Object value) throws Throwable {
            return ((int)red4.invokeExact(value))*17<<16 | ((int)green4.invokeExact(value))*17<<8 | ((int)blue4.invokeExact(value))*17;
        }
    }
}
