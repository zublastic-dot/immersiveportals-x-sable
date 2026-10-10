package qouteall.imm_ptl.core.compat.dh_compatibility;

import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import de.nick1st.imm_ptl.events.ClientExitEvent;
import de.nick1st.imm_ptl.events.DimensionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Retains existing DH caches near direct portals; never creates or ticks a DH world. */
public final class DhNearbyLevelRetention {
    static final int MAX_REMOTE_WORLDS = 4;
    static final double ENTER_DISTANCE = 64, EXIT_DISTANCE = 80;
    static final long REFRESH_NANOS = 1_000_000_000L, FRESH_NANOS = 5_000_000_000L;
    private static final Policy<ClientLevel> POLICY = new Policy<>();
    private static boolean initialized;

    private DhNearbyLevelRetention() {}

    /** No DH class references: safe when the optional mod is absent or unsupported. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        String version = ModList.get().getModContainerById("distanthorizons")
            .map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
        if (!DhCompatibility.supports(version)) return;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> refresh());
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> POLICY.clear());
        NeoForge.EVENT_BUS.addListener(ClientExitEvent.class, event -> POLICY.clear());
        NeoForge.EVENT_BUS.addListener(DimensionEvents.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.class,
            event -> POLICY.remove(world -> world.dimension() == event.level));
    }

    private static void refresh() {
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        // Minecraft.level may temporarily be another portal render's world.
        if (mc.player == null || connection == null || !ClientWorldLoader.getIsInitialized()) {
            POLICY.clear();
            return;
        }
        ClientLevel actual = (ClientLevel) mc.player.level();
        long now = System.nanoTime();
        if (!POLICY.refreshDue(connection, actual, now)) return;

        Map<ResourceKey<Level>, ClientLevel> loaded = new HashMap<>();
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) loaded.put(world.dimension(), world);
        Selection<ClientLevel> selection = POLICY.select(connection, actual, now);
        Vec3 position = mc.player.getEyePosition();
        // Scan loaded entities, not their section index: very large openings and
        // Sable's transformed portal poses can be far from their indexed centre.
        for (var entity : actual.entitiesForRendering()) {
            if (entity instanceof Portal portal) offer(selection, portal, actual, position, loaded);
        }
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(actual)) {
            offer(selection, portal, actual, position, loaded);
        }
        POLICY.publish(selection);
    }

    private static void offer(Selection<ClientLevel> selection, Portal portal, ClientLevel actual,
                              Vec3 position, Map<ResourceKey<Level>, ClientLevel> loaded) {
        if (portal.isRemoved() || portal.level() != actual || portal.getDestDim() == null
            || portal.getAxisW() == null || portal.getAxisH() == null
            || !(portal.getWidth() > 0) || !(portal.getHeight() > 0)) return;
        ClientLevel destination = loaded.get(portal.getDestDim());
        if (destination == null) return;
        // This uses the current portal shape/orientation, including ship poses;
        // it is neither a distance to the entity centre nor a camera/frustum test.
        selection.offer(destination, portal.getDistanceToNearestPointInPortal(position));
    }

    /** Called under DH's wrapper lock: only immutable weak identities and a monotonic clock. */
    public static boolean shouldRetain(ClientLevel world) {
        return POLICY.retains(world, System.nanoTime());
    }

    /** Pure identity policy, shared with the tests; only the client thread publishes. */
    static final class Policy<T> {
        private volatile Snapshot<T> snapshot = Snapshot.empty();

        boolean retains(T world, long now) { return snapshot.retains(world, now); }
        boolean refreshDue(Object session, T actual, long now) {
            Snapshot<T> current = snapshot;
            long elapsed = now - current.seenNanos;
            return !current.sameOwner(session, actual) || elapsed < 0 || elapsed >= REFRESH_NANOS;
        }
        Selection<T> select(Object session, T actual, long now) {
            return new Selection<>(snapshot, session, actual, now);
        }
        void publish(Selection<T> selection) { snapshot = selection.finish(); }
        void clear() { snapshot = Snapshot.empty(); }
        void remove(Predicate<T> removed) { snapshot = snapshot.without(removed); }
    }

    static final class Selection<T> {
        private final Snapshot<T> previous;
        private final Object session;
        private final T actual;
        private final long now;
        private final List<Candidate<T>> nearest = new ArrayList<>(MAX_REMOTE_WORLDS);

        Selection(Snapshot<T> previous, Object session, T actual, long now) {
            this.previous = previous;
            this.session = session;
            this.actual = actual;
            this.now = now;
        }

        void offer(T world, double distance) {
            if (session == null || actual == null || world == null || world == actual
                || !Double.isFinite(distance) || distance < 0) return;
            boolean retained = previous.session.get() == session && previous.fresh(now)
                && previous.remoteContains(world);
            if (distance > (retained ? EXIT_DISTANCE : ENTER_DISTANCE)) return;
            for (int i = 0; i < nearest.size(); i++) {
                Candidate<T> old = nearest.get(i);
                if (old.world == world) {
                    if (distance >= old.distance) return;
                    nearest.remove(i);
                    break;
                }
            }
            nearest.add(new Candidate<>(world, distance, retained));
            nearest.sort(Comparator.<Candidate<T>>comparingDouble(candidate -> candidate.distance)
                .thenComparing(candidate -> !candidate.retained));
            if (nearest.size() > MAX_REMOTE_WORLDS) nearest.remove(MAX_REMOTE_WORLDS);
        }

        Snapshot<T> finish() {
            if (session == null || actual == null) return Snapshot.empty();
            return new Snapshot<>(new WeakReference<>(session), new WeakReference<>(actual),
                nearest.stream().map(candidate -> new WeakReference<>(candidate.world)).toList(), now);
        }
    }

    private record Candidate<T>(T world, double distance, boolean retained) {}

    static final class Snapshot<T> {
        final WeakReference<Object> session;
        final WeakReference<T> actual;
        final List<WeakReference<T>> remote;
        final long seenNanos;

        Snapshot(WeakReference<Object> session, WeakReference<T> actual,
                 List<WeakReference<T>> remote, long seenNanos) {
            this.session = session;
            this.actual = actual;
            this.remote = List.copyOf(remote);
            this.seenNanos = seenNanos;
        }
        static <T> Snapshot<T> empty() {
            return new Snapshot<>(new WeakReference<>(null), new WeakReference<>(null), List.of(), 0);
        }
        boolean fresh(long now) {
            long elapsed = now - seenNanos;
            return elapsed >= 0 && elapsed < FRESH_NANOS && session.get() != null;
        }
        boolean sameOwner(Object owner, T world) {
            return owner != null && world != null && session.get() == owner && actual.get() == world;
        }
        boolean remoteContains(T world) {
            if (world == null) return false;
            for (WeakReference<T> reference : remote) if (reference.get() == world) return true;
            return false;
        }
        boolean retains(T world, long now) {
            return world != null && fresh(now) && (actual.get() == world || remoteContains(world));
        }
        Snapshot<T> without(Predicate<T> removed) {
            T playerWorld = actual.get();
            if (playerWorld != null && removed.test(playerWorld)) return empty();
            List<WeakReference<T>> remaining = new ArrayList<>(MAX_REMOTE_WORLDS);
            for (WeakReference<T> reference : remote) {
                T world = reference.get();
                if (world != null && !removed.test(world)) remaining.add(reference);
            }
            // Removal must not renew the freshness lease.
            return new Snapshot<>(session, actual, remaining, seenNanos);
        }
    }
}
