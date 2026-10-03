package qouteall.imm_ptl.core.compat.sound_physics;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.mixin.client.sound.IEPortalSoundChannel;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Optional SPA adapter. Never changes Minecraft.level or its active acoustic provider. */
public final class PortalSoundPhysics {
    private static final Map<SoundInstance, Prepared> PREPARED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Integer, Update> UPDATES = new ConcurrentHashMap<>();
    private static final RefreshContexts CONTEXTS = new RefreshContexts();
    private static final ThreadLocal<Prepared> EVALUATION = new ThreadLocal<>();
    private static volatile Api api;
    private static volatile boolean attempted;
    private static volatile boolean linked;
    private static boolean warned;
    private static final AtomicLong evaluations = new AtomicLong();
    private static final AtomicLong pending = new AtomicLong();

    private PortalSoundPhysics() {}
    public static void linked() { linked = true; }

    record Prepared(PortalSoundManager.Route route, PortalAcousticScene scene) {}
    record Update(SoundInstance sound, boolean throughPortal, long epoch, Object scene, long nanos) {}

    /** Owner-thread capture, before any sound-executor submission. */
    public static void prepare(SoundInstance sound, PortalSoundManager.Route route) {
        Api methods = api();
        if (methods == null) return;
        try {
            // SPA's six-argument overload invents a start context. A refresh must
            // retain the actual stream/class/loop semantics without reusing that
            // thread's last unrelated sound or counting another playback start.
            if (CONTEXTS.capture(sound, methods.contexts()) == null) return;
        } catch (ReflectiveOperationException | LinkageError failure) {
            warn(failure);
            return;
        }
        if (!route.throughPortal() || !route.reachable()) {
            PREPARED.remove(sound);
            return;
        }
        var source = PortalAcousticSnapshot.request(route.sourceWorld(), bounds(route.sourcePosition(), route.entryPosition()));
        var listener = PortalAcousticSnapshot.request(route.listenerWorld(), bounds(route.exitPosition(), route.listenerPosition()));
        if (PREPARED.size() >= PortalSoundManager.MAX_SOURCES && !PREPARED.containsKey(sound)) return;
        Prepared old = PREPARED.get(sound);
        if (old != null && old.route().equals(route) && old.scene() != null
            && old.scene().sourceSnapshot() == source && old.scene().listenerSnapshot() == listener) return;
        PREPARED.put(sound, new Prepared(route, source == null || listener == null ? null
            : new PortalAcousticScene(route, source, listener)));
    }

    private static AABB bounds(Vec3 a, Vec3 b) { return new AABB(a, b).inflate(6); }

    /** A finite refresh on the native audio executor; ordinary local voices remain native. */
    public static void update(Channel channel, SoundInstance sound, PortalSoundManager.Route route) {
        Api methods = api();
        if (methods == null || !(channel instanceof IEPortalSoundChannel accessor)) return;
        Object context = CONTEXTS.get(sound);
        if (context == null) return; // never resolve a live SoundInstance on the audio executor
        int id = accessor.portal$getSourceId();
        Update previous = UPDATES.get(id);
        if (!route.reachable()) { UPDATES.remove(id); return; }
        if (!route.throughPortal() && previous == null) return;
        Prepared prepared = PREPARED.get(sound);
        Object scene = prepared == null ? null : prepared.scene();
        long now = System.nanoTime();
        if (previous != null && previous.sound() == sound && previous.throughPortal() == route.throughPortal()
            && now - previous.nanos() < 250_000_000L) return;
        if (UPDATES.size() >= PortalSoundManager.MAX_SOURCES && previous == null) return;
        UPDATES.put(id, new Update(sound, route.throughPortal(), route.epoch(), scene, now));
        try {
            Vec3 pos = route.throughPortal() ? route.virtualSourcePosition() : route.sourcePosition();
            methods.process.invoke(null, id, pos.x, pos.y, pos.z, sound.getSource(), sound.getLocation(), false, context);
        } catch (ReflectiveOperationException | LinkageError failure) {
            warn(failure);
        } finally {
            // SPA may choose a reflected position. Native attenuation/panning must
            // still use the complete portal path rather than a raw remote coordinate.
            channel.setSelfPosition(route.presentationPosition());
        }
        if (!route.throughPortal()) UPDATES.remove(id);
    }

    public static PortalSoundManager.Route begin(int source) {
        PortalSoundManager.Route route = PortalSoundManager.route(source);
        if (route == null || !route.throughPortal() || !route.reachable()) return null;
        Prepared prepared = PREPARED.get(PortalSoundManager.sound(source));
        if (prepared != null && prepared.route().epoch() == route.epoch()) EVALUATION.set(prepared);
        else EVALUATION.set(new Prepared(route, null));
        evaluations.incrementAndGet();
        return route;
    }

    public static void end() { EVALUATION.remove(); }
    public static boolean evaluatingPortal() { return EVALUATION.get() != null; }

    /** Called instead of SPA's dimension-less scene selection only for a routed voice. */
    public static Object scene() {
        Prepared prepared = EVALUATION.get();
        if (prepared == null || prepared.scene() == null) { pending.incrementAndGet(); return null; }
        try { return prepared.scene().asSpaScene(); }
        catch (ReflectiveOperationException | LinkageError failure) { warn(failure); return null; }
    }

    public static void retain(Collection<SoundInstance> sounds) {
        var worlds = Collections.newSetFromMap(new IdentityHashMap<net.minecraft.client.multiplayer.ClientLevel, Boolean>());
        synchronized (PREPARED) {
            PREPARED.keySet().removeIf(sound -> !sounds.contains(sound));
            for (Prepared prepared : PREPARED.values()) {
                worlds.add(prepared.route().sourceWorld()); worlds.add(prepared.route().listenerWorld());
            }
        }
        PortalAcousticSnapshot.retain(worlds);
        UPDATES.entrySet().removeIf(entry -> !sounds.contains(entry.getValue().sound()));
        CONTEXTS.retain(sounds);
    }

    public static void clear() {
        PREPARED.clear(); UPDATES.clear(); CONTEXTS.clear(); EVALUATION.remove(); PortalAcousticSnapshot.clear();
        evaluations.set(0); pending.set(0);
    }

    public static String diagnostics() {
        return "SPA=" + (api() != null) + " linked=" + linked + " prepared=" + PREPARED.size() + " evaluations=" + evaluations.get()
            + " pendingGeometry=" + pending.get();
    }

    private static Api api() {
        if (attempted) return api;
        synchronized (PortalSoundPhysics.class) {
            if (attempted) return api;
            try {
                Class.forName("com.sonicether.soundphysics.acoustic.AcousticScenes", false, PortalSoundPhysics.class.getClassLoader());
                Class<?> type = Class.forName("com.sonicether.soundphysics.SoundPhysics", true, PortalSoundPhysics.class.getClassLoader());
                if (!linked) throw new IllegalStateException("SPA present but portal acoustic mixins were not linked");
                ContextApi contexts = ContextApi.load(PortalSoundPhysics.class.getClassLoader());
                api = new Api(type.getMethod("processSound", int.class, double.class, double.class, double.class,
                    SoundSource.class, ResourceLocation.class, boolean.class, contexts.context().getReturnType()), contexts);
            } catch (ClassNotFoundException ignored) {
                // SPA is optional; vanilla and native Sable audio do not acquire a hard dependency.
            } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) { warn(failure); }
            attempted = true;
            return api;
        }
    }
    private record Api(Method process, ContextApi contexts) {}

    /** Optional installed-provider contract, resolved once; no Minecraft/world access. */
    record ContextApi(Method resolve, Method context) {
        static ContextApi load(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> resolver = Class.forName("com.sonicether.soundphysics.SoundInstanceResolver", false, loader);
            Class<?> resolved = Class.forName("com.sonicether.soundphysics.SoundInstanceResolver$ResolvedSound", false, loader);
            Class<?> context = Class.forName("com.sonicether.soundphysics.SoundPhysicsSoundPolicy$SoundContext", false, loader);
            return new ContextApi(resolver.getMethod("resolve", SoundInstance.class),
                context.getMethod("fromResolved", resolved, SoundInstance.class, SoundSource.class, boolean.class, boolean.class));
        }

        Object capture(SoundInstance sound, Sound selected) throws ReflectiveOperationException {
            return context.invoke(null, resolve.invoke(null, sound), sound, sound.getSource(),
                selected != null && selected.shouldStream(), false);
        }
    }

    /** Identity lifetime matches native playback; immutable values are published to audio consumers. */
    static final class RefreshContexts {
        private record Captured(Sound selected, Object context) {}
        private final Map<SoundInstance, Captured> entries = Collections.synchronizedMap(new IdentityHashMap<>());

        Object capture(SoundInstance sound, ContextApi api) throws ReflectiveOperationException {
            Sound selected = sound.getSound();
            Captured old = entries.get(sound);
            if (old != null && old.selected() == selected) return old.context();
            if (old == null && entries.size() >= PortalSoundManager.MAX_SOURCES) return null;
            entries.remove(sound); // a failed replacement must not publish stale semantics
            Object context = api.capture(sound, selected);
            entries.put(sound, new Captured(selected, context));
            return context;
        }

        Object get(SoundInstance sound) {
            Captured value = entries.get(sound);
            return value == null ? null : value.context();
        }
        void retain(Collection<SoundInstance> sounds) {
            synchronized (entries) { entries.keySet().removeIf(sound -> !sounds.contains(sound)); }
        }
        void clear() { entries.clear(); }
    }
    private static synchronized void warn(Throwable failure) {
        if (warned) return;
        warned = true;
        LogUtils.getLogger().warn("[IP sound] SPA portal acoustics unavailable; no wrong-world scene substitution", failure);
    }
}
