package qouteall.imm_ptl.core.teleportation;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import ipl.sable.SableBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sound_physics.PortalSoundPhysics;
import qouteall.imm_ptl.core.compat.sound_physics.PortalNativeSoundPolicy;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;
import qouteall.imm_ptl.core.mixin.client.sound.IEPortalSoundChannel;
import qouteall.imm_ptl.core.mixin.client.sound.IEPortalEntityBoundSound;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns positional sound provenance, not playback. Native instances, stream cursors,
 * channel handles, ticking, pitch and explicit stop keys are never replaced.
 * World queries happen only on the client thread; audio consumers read immutable routes.
 */
public final class PortalSoundManager {
    public static final int MAX_SOURCES = 2048;
    public static final int MAX_PORTALS_PER_SOURCE = 128;
    public static final double MAX_PATH_DISTANCE = 256;
    private static final int MAX_CACHED_WORLDS = 16, MAX_CACHED_PORTALS = 2048;
    private static final Logger LOG = LogUtils.getLogger();
    private static final PortalSoundRegistry<SoundInstance, ClientLevel> OWNERS = new PortalSoundRegistry<>(MAX_SOURCES);
    private static final ThreadLocal<ClientLevel> PRODUCER = new ThreadLocal<>();
    private static volatile Map<SoundInstance, Route> published = Collections.emptyMap();
    private static final Map<SoundInstance, Route> working = new IdentityHashMap<>();
    private static final Map<Integer, SoundInstance> nativeSources = new ConcurrentHashMap<>();
    private static final Map<ClientLevel, List<Portal>> portalCache = new IdentityHashMap<>();
    private static Object connection;
    private static long tick, epoch;
    private static int diagnosticCount;

    private PortalSoundManager() {}

    public record Route(ClientLevel sourceWorld, ClientLevel listenerWorld, Vec3 sourcePosition,
                        Vec3 entryPosition, Vec3 exitPosition, Vec3 listenerPosition,
                        Vec3 presentationPosition, Vec3 virtualSourcePosition, double totalDistance,
                        long epoch, @Nullable UUID portalId, boolean reachable, boolean throughPortal,
                        @Nullable PortalSoundPath.Frame frame) {}

    /** This does not query a Level and is safe on the native sound executor. */
    @Nullable public static Route route(SoundInstance sound) { return published.get(sound); }
    @Nullable public static Route route(int sourceId) {
        SoundInstance sound = nativeSources.get(sourceId);
        return sound == null ? null : route(sound);
    }
    @Nullable public static SoundInstance sound(int sourceId) { return nativeSources.get(sourceId); }
    public static void associateChannel(SoundInstance sound, Channel channel) {
        if (channel instanceof IEPortalSoundChannel accessor) {
            int source = accessor.portal$getSourceId();
            Route route = route(sound);
            if (route != null && (nativeSources.containsKey(source) || nativeSources.size() < MAX_SOURCES)) {
                nativeSources.put(source, sound);
            }
            else nativeSources.remove(source);
            if (channel instanceof PortalSoundChannelState state) state.portal$setMuted(route != null && !route.reachable());
        }
    }
    public static void forgetChannel(int sourceId) { nativeSources.remove(sourceId); }

    /** Bounded manual diagnostic, never a per-frame log or a live-world query. */
    public static String diagnostics() {
        Map<SoundInstance, Route> routes = published;
        StringBuilder text = new StringBuilder("owners=").append(OWNERS.size())
            .append(" liveRoutes=").append(routes.size()).append(" nativeVoices=").append(nativeSources.size());
        nativeSources.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(16).forEach(entry -> {
            Route route = routes.get(entry.getValue());
            if (route == null) return;
            text.append("\nsourceId=").append(entry.getKey()).append(" sound=").append(entry.getValue().getLocation())
                .append(" source=").append(route.sourceWorld().dimension().location())
                .append(" listener=").append(route.listenerWorld().dimension().location())
                .append(" reachable=").append(route.reachable()).append(" routed=").append(route.throughPortal())
                .append(" emitter=").append(route.sourcePosition()).append(" entry=").append(route.entryPosition())
                .append(" exit=").append(route.exitPosition()).append(" path=").append(route.totalDistance())
                .append(" presentation=").append(route.presentationPosition()).append(" epoch=").append(route.epoch());
        });
        return text.toString();
    }

    public static Scope producer(ClientLevel world) {
        ClientLevel previous = PRODUCER.get();
        PRODUCER.set(world);
        return () -> { if (previous == null) PRODUCER.remove(); else PRODUCER.set(previous); };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }

    /** Called before immediate or delayed playback while the producer's Level is still known. */
    public static boolean bind(SoundInstance sound) {
        Minecraft mc = Minecraft.getInstance();
        if (!IPGlobal.enableCrossPortalSound || !mc.isSameThread() || !eligible(sound)) return true;
        ensureSession(mc);
        if (OWNERS.owner(sound) != null) return true;
        SoundInstance underlying = SableBridge.PRESENT ? SableSources.underlying(sound) : sound;
        var inherited = OWNERS.owner(underlying);
        ClientLevel world = inherited == null ? PRODUCER.get() : inherited.world();
        if (world == null && PacketRedirectionClient.getIsProcessingRedirectedMessage()) {
            var dimension = PacketRedirectionClient.clientTaskRedirection.get();
            if (ClientWorldLoader.getIsInitialized()) {
                for (ClientLevel candidate : ClientWorldLoader.getClientWorlds()) {
                    if (candidate.dimension() == dimension) { world = candidate; break; }
                }
            }
        }
        // Rendering temporarily swaps Minecraft.level. The physical player world is
        // the fallback only; explicit producer/packet ownership always wins.
        if (world == null && mc.player != null && mc.player.level() instanceof ClientLevel level) world = level;
        // Positional playback without a loaded player is not world transport (for
        // example a menu preview). A saturated registry must not route a remote
        // voice as if its coordinates belonged to the listener's world.
        if (world == null) return true;
        return OWNERS.bind(sound, world, tick);
    }

    /** NeoForge listeners may return another instance; carry provenance only. */
    public static boolean inherit(SoundInstance original, SoundInstance replacement) {
        if (replacement == null || replacement == original) return true;
        var owner = OWNERS.owner(original);
        if (owner == null || !eligible(replacement)) return bind(replacement);
        return OWNERS.bind(replacement, owner.world(), tick);
    }

    /** Preserve vanilla delay semantics, measured along the same physical route. */
    public static double delayDistanceSquared(ClientLevel world, Vec3 source, Vec3 listener, double nativeDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (!IPGlobal.enableCrossPortalSound || !mc.isSameThread() || mc.player == null
            || !(mc.player.level() instanceof ClientLevel listenerWorld)) return nativeDistance;
        PhysicalSource physical = SableBridge.PRESENT ? SableSources.resolvePoint(world, source)
            : new PhysicalSource(world, source);
        if (physical == null || !PortalSoundPath.finite(physical.position()) || !PortalSoundPath.finite(listener)) return 0;
        Route route = calculate(physical.world(), listenerWorld, physical.position(), listener);
        // An unreachable voice starts silently, rather than waiting millions of
        // ticks for an unrelated plot-space coordinate before being routed.
        return route.reachable() ? route.totalDistance() * route.totalDistance() : 0;
    }

    /** Native constructor hook, after NeoForge/Sable have selected the effective instance. */
    public static Vec3 presentation(SoundInstance sound, Vec3 original, Vec3 listener) {
        Minecraft mc = Minecraft.getInstance();
        if (!IPGlobal.enableCrossPortalSound || !mc.isSameThread() || !eligible(sound)) return original;
        bind(sound);
        Route result = calculate(sound, listener, mc);
        if (result == null) return original;
        result = stableEpoch(sound, result);
        working.put(sound, result);
        PortalSoundPhysics.prepare(sound, result);
        publish();
        return result.presentationPosition();
    }

    public static void afterPlay(SoundInstance sound, Map<SoundInstance, ChannelAccess.ChannelHandle> channels,
                                 Vec3 listener) {
        if (!IPGlobal.enableCrossPortalSound) return;
        ChannelAccess.ChannelHandle handle = channels.get(sound);
        if (handle == null) return;
        bind(sound);
        Runnable update = apply(sound, handle, listener, Minecraft.getInstance());
        publish();
        if (update != null) update.run();
    }

    /** Called after native ticks: wrappers have already updated their emitter position. */
    public static void tick(Map<SoundInstance, ChannelAccess.ChannelHandle> channels,
                            Set<SoundInstance> queued, Vec3 listener) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        ensureSession(mc);
        tick++;
        portalCache.clear();
        if (mc.player == null) { clear(); return; }
        Set<ClientLevel> loaded = loadedWorlds(mc);
        Set<SoundInstance> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(channels.keySet());
        retained.addAll(queued);
        List<Runnable> updates = new ArrayList<>();
        for (var item : channels.entrySet()) {
            SoundInstance sound = item.getKey();
            updateEntityWorld(sound);
            var owner = OWNERS.owner(sound);
            if (owner == null) continue;
            if (!loaded.contains(owner.world())) {
                item.getValue().execute(Channel::stop);
                working.remove(sound);
                continue;
            }
            Runnable update = apply(sound, item.getValue(), listener, mc);
            if (update != null) updates.add(update);
        }
        OWNERS.prune(retained::contains, loaded::contains, tick);
        working.keySet().removeIf(sound -> !retained.contains(sound) || OWNERS.owner(sound) == null);
        PortalSoundPhysics.retain(working.keySet());
        PortalNativeSoundPolicy.retain(retained);
        publish();
        // Audio jobs may run immediately. Publish every matching route/geometry
        // epoch before any job consults sourceId -> sound -> immutable route.
        updates.forEach(Runnable::run);
    }

    @Nullable private static Runnable apply(SoundInstance sound, ChannelAccess.ChannelHandle handle, Vec3 listener,
                                           Minecraft mc) {
        Route previous = working.get(sound);
        Route next = IPGlobal.enableCrossPortalSound ? calculate(sound, listener, mc) : null;
        if (next == null) {
            if (previous != null && (previous.throughPortal() || !previous.reachable())) {
                Vec3 original = position(sound);
                working.remove(sound);
                return () -> handle.execute(channel -> {
                    if (channel instanceof PortalSoundChannelState state) state.portal$setMuted(false);
                    channel.setSelfPosition(original);
                });
            }
            working.remove(sound);
            return null;
        }
        next = stableEpoch(sound, next);
        working.put(sound, next);
        PortalSoundPhysics.prepare(sound, next);
        boolean changedPresentation = next.throughPortal() || !next.reachable()
            || previous != null && (previous.throughPortal() || !previous.reachable());
        if (!changedPresentation) return null; // ordinary native/Sable audio stays native
        Vec3 position = next.presentationPosition();
        Route route = next;
        if (diagnosticCount < 16 && (previous == null || previous.reachable() != next.reachable()
            || previous.throughPortal() != next.throughPortal() || previous.listenerWorld() != next.listenerWorld())) {
            diagnosticCount++;
            LOG.info("[IP sound] source={} listener={} routed={} reachable={} path={} epoch={}",
                next.sourceWorld().dimension().location(), next.listenerWorld().dimension().location(),
                next.throughPortal(), next.reachable(), next.totalDistance(), next.epoch());
        }
        return () -> handle.execute(channel -> {
            if (channel instanceof PortalSoundChannelState state) state.portal$setMuted(!route.reachable());
            channel.setSelfPosition(position);
            PortalSoundPhysics.update(channel, sound, route);
        });
    }

    @Nullable private static Route calculate(SoundInstance sound, Vec3 listener, Minecraft mc) {
        if (!eligible(sound)) return null;
        updateEntityWorld(sound);
        var owner = OWNERS.owner(sound);
        if (owner == null || mc.player == null || !(mc.player.level() instanceof ClientLevel listenerWorld)
            || !PortalSoundPath.finite(listener)) return null;
        PhysicalSource physical = SableBridge.PRESENT
            ? SableSources.resolve(owner.world(), sound) : new PhysicalSource(owner.world(), position(sound));
        if (physical == null || !PortalSoundPath.finite(physical.position())) {
            return silent(owner.world(), listenerWorld, Vec3.ZERO, listener);
        }
        ClientLevel sourceWorld = physical.world();
        Vec3 source = physical.position();
        if (!loadedWorlds(mc).contains(sourceWorld)) return silent(sourceWorld, listenerWorld, source, listener);
        return calculate(sourceWorld, listenerWorld, source, listener);
    }

    private static Route calculate(ClientLevel sourceWorld, ClientLevel listenerWorld, Vec3 source, Vec3 listener) {
        Route best = sourceWorld == listenerWorld
            ? direct(sourceWorld, listenerWorld, source, listener) : null;
        // A direct path shorter than a centimetre cannot be improved appreciably.
        if (best != null && best.totalDistance() < .01) return best;
        List<Portal> candidates = new ArrayList<>();
        for (Portal portal : loadedPortals(sourceWorld)) {
            if (candidates.size() < MAX_PORTALS_PER_SOURCE && !portal.isRemoved()
                && !(portal instanceof Mirror) && portal.getDestDim() == listenerWorld.dimension()
                && portal.getPortalShape() instanceof RectangularPortalShape
                && Math.abs(portal.getScaling() - 1) < 1e-6
                && portal.getDistanceToNearestPointInPortal(source) <= MAX_PATH_DISTANCE) candidates.add(portal);
        }
        for (Portal portal : candidates) {
            PortalSoundPath.Frame frame = new PortalSoundPath.Frame(portal.getOriginPos(), portal.getDestPos(),
                portal.getAxisW(), portal.getAxisH(), portal.transformLocalVecNonScale(portal.getAxisW()),
                portal.transformLocalVecNonScale(portal.getAxisH()), portal.getWidth() / 2, portal.getHeight() / 2);
            PortalSoundPath.Path path = PortalSoundPath.solve(frame, source, listener);
            if (path == null || path.distance() > MAX_PATH_DISTANCE
                || best != null && path.distance() >= best.totalDistance()) continue;
            best = new Route(sourceWorld, listenerWorld, source, path.entry(), path.exit(), listener,
                path.presentation(), path.virtualSource(), path.distance(), ++epoch, portal.getUUID(), true, true, frame);
        }
        if (best != null) return best;
        // Keep a stream alive at zero gain, without a fictitious acoustic route.
        return silent(sourceWorld, listenerWorld, source, listener);
    }

    private static Route silent(ClientLevel sourceWorld, ClientLevel listenerWorld, Vec3 source, Vec3 listener) {
        return new Route(sourceWorld, listenerWorld, source, source, source, listener,
            listener.add(0, 1_000_000, 0), source, Double.POSITIVE_INFINITY,
            ++epoch, null, false, false, null);
    }

    private static Route direct(ClientLevel sourceWorld, ClientLevel listenerWorld, Vec3 source, Vec3 listener) {
        return new Route(sourceWorld, listenerWorld, source, source, source, listener, source, source,
            source.distanceTo(listener), ++epoch, null, true, false, null);
    }

    /** Existing client entities only; one scan per source world/tick, never a chunk request. */
    private static List<Portal> loadedPortals(ClientLevel world) {
        List<Portal> cached = portalCache.get(world);
        if (cached != null) return cached;
        if (portalCache.size() >= MAX_CACHED_WORLDS) return List.of();
        List<Portal> found = new ArrayList<>();
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(world)) {
            if (found.size() == MAX_CACHED_PORTALS) break;
            found.add(portal);
        }
        for (var entity : world.entitiesForRendering()) {
            if (found.size() == MAX_CACHED_PORTALS) break;
            if (entity instanceof Portal portal) found.add(portal);
        }
        List<Portal> result = List.copyOf(found);
        portalCache.put(world, result);
        return result;
    }

    private static Route stableEpoch(SoundInstance sound, Route next) {
        Route previous = working.get(sound);
        if (previous == null || !epoch(next, 0).equals(epoch(previous, 0))) return next;
        return epoch(next, previous.epoch());
    }
    private static Route epoch(Route r, long value) {
        return new Route(r.sourceWorld(), r.listenerWorld(), r.sourcePosition(), r.entryPosition(), r.exitPosition(),
            r.listenerPosition(), r.presentationPosition(), r.virtualSourcePosition(), r.totalDistance(), value,
            r.portalId(), r.reachable(), r.throughPortal(), r.frame());
    }

    static boolean eligible(SoundInstance sound) {
        return sound != null && !sound.isRelative() && sound.getAttenuation() == SoundInstance.Attenuation.LINEAR
            && sound.getSource() != SoundSource.MUSIC
            && !PortalNativeSoundPolicy.keepsNativeOwnership(sound, SableBridge.PRESENT ? SableSources.underlying(sound) : sound);
    }

    private static void updateEntityWorld(SoundInstance sound) {
        SoundInstance original = SableBridge.PRESENT ? SableSources.underlying(sound) : sound;
        if (original instanceof IEPortalEntityBoundSound emitter
            && emitter.portal$getEmitter().level() instanceof ClientLevel world) OWNERS.moveEmitter(sound, world);
    }

    private static Vec3 position(SoundInstance sound) { return new Vec3(sound.getX(), sound.getY(), sound.getZ()); }
    private static Set<ClientLevel> loadedWorlds(Minecraft mc) {
        Set<ClientLevel> loaded = Collections.newSetFromMap(new IdentityHashMap<>());
        if (mc.player != null && mc.player.level() instanceof ClientLevel world) loaded.add(world);
        if (ClientWorldLoader.getIsInitialized()) loaded.addAll(ClientWorldLoader.getClientWorlds());
        return loaded;
    }
    private static void ensureSession(Minecraft mc) {
        Object current = mc.getConnection();
        if (connection != current) { clear(); connection = current; }
    }
    private static void publish() { published = Collections.unmodifiableMap(new IdentityHashMap<>(working)); }
    public static void clear() {
        OWNERS.clear(); working.clear(); published = Collections.emptyMap(); nativeSources.clear(); portalCache.clear();
        PortalSoundPhysics.clear();
        PortalNativeSoundPolicy.clear();
        tick = 0; epoch++; diagnosticCount = 0; connection = null;
    }

    private record PhysicalSource(ClientLevel world, Vec3 position) {}

    /** Optional linkage isolated behind SableBridge.PRESENT. */
    private static final class SableSources {
        static SoundInstance underlying(SoundInstance sound) {
            return sound instanceof dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate delegate
                ? delegate.instance : sound;
        }

        @Nullable static PhysicalSource resolve(ClientLevel storageWorld, SoundInstance sound) {
            SoundInstance underlying = underlying(sound);
            Vec3 raw = position(underlying);
            var sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(storageWorld, raw);
            if (sub == null) return new PhysicalSource(storageWorld, position(sound));
            if (sub.isRemoved()) return null;
            var parent = sub instanceof ipl.sable.duck.IplSubLevelDuck duck ? duck.ipl$getParentLevel() : sub.getLevel();
            if (!(parent instanceof ClientLevel world)) return null;
            // Delegate coordinates are already physical. Plain known plot sources
            // are transformed once, using the same logical pose as native Sable audio.
            Vec3 physical = underlying == sound ? sub.logicalPose().transformPosition(raw) : position(sound);
            return new PhysicalSource(world, physical);
        }

        @Nullable static PhysicalSource resolvePoint(ClientLevel storageWorld, Vec3 raw) {
            var sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(storageWorld, raw);
            if (sub == null) return new PhysicalSource(storageWorld, raw);
            if (sub.isRemoved()) return null;
            var parent = sub instanceof ipl.sable.duck.IplSubLevelDuck duck ? duck.ipl$getParentLevel() : sub.getLevel();
            return parent instanceof ClientLevel world ? new PhysicalSource(world, sub.logicalPose().transformPosition(raw)) : null;
        }
    }
}
