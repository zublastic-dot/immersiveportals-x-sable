package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.Collection;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Render-thread source shadow snapshots. Each publication owns its depth image rather than
 * borrowing an Iris texture which another portal render can overwrite. A returned snapshot is
 * borrowed until the next capture/lifecycle operation, never a resource owned by the caller.
 */
public final class PortalSourceShadow {
    static final int MAX_WORLDS = 4, MAX_RESOLUTION = 4096;
    static final long MAX_BYTES = 64L * 1024 * 1024, MAX_AGE_NANOS = 2_000_000_000L;
    private static final Store STORE = new Store(MAX_WORLDS, MAX_BYTES);
    private static boolean initialized, warned;
    private static int diagnostics;

    private PortalSourceShadow() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(de.nick1st.imm_ptl.events.ClientExitEvent.class, event -> clear());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (Minecraft.getInstance().level == null || !PortalShaderLighting.supported()) clear();
            else if (ClientWorldLoader.getIsInitialized()) retain(ClientWorldLoader.getClientWorlds());
        });
    }

    public static @Nullable Snapshot get(ClientLevel source) {
        return source == null ? null : STORE.get(source, source.getGameTime(), source.getDayTime(), System.nanoTime());
    }

    public static void capture(ClientLevel source, Object pipeline, int depthTexture, int resolution,
                               Vec3 camera, Matrix4fc modelView, Matrix4fc projection,
                               float shadowDistance, float distanceRenderMultiplier, float sourceSunAngle) {
        if (source == null || pipeline == null) return;
        try {
            Snapshot snapshot = STORE.capture(source, pipeline, new Capture(depthTexture, resolution, camera,
                modelView, projection, shadowDistance, distanceRenderMultiplier, sourceSunAngle,
                source.getGameTime(), source.getDayTime()), System.nanoTime());
            if (snapshot == null && !warned) {
                warned = true;
                LogUtils.getLogger().warn("[IP shader light] source shadow snapshot unavailable: invalid native depth or bounded storage; retaining strict source fallback");
            } else if (snapshot != null && diagnostics < 8) {
                diagnostics++;
                LogUtils.getLogger().info("[IP shader light] source shadow GPU snapshot for {}: {}px, distance {}, generation {}, {} owned bytes",
                    source.dimension().location(), resolution, shadowDistance, snapshot.generation(), STORE.bytes());
            }
        } catch (RuntimeException failure) {
            // This optional readback path must not interrupt the native source render.
            if (!warned) {
                warned = true;
                LogUtils.getLogger().warn("[IP shader light] source shadow snapshot failed; retaining strict source fallback", failure);
            }
        }
    }

    public static void invalidatePipeline(Object pipeline) { STORE.invalidatePipeline(pipeline); }
    public static void retain(Collection<ClientLevel> worlds) { STORE.retain(worlds); }
    public static void clear() { STORE.clear(); warned = false; diagnostics = 0; }

    /** Immutable camera/time/matrix metadata paired with one owned, unchanged depth image. */
    public static final class Snapshot {
        private final PortalSourceShadowDepth image;
        private final Vec3 camera, towardLight;
        private final Matrix4f modelView, projection, inverseProjectionModelView;
        private final float shadowDistance, distanceRenderMultiplier, sourceSunAngle;
        private final long sourceGameTime, sourceDayTime, generation, capturedNanos;
        private boolean available = true;

        private Snapshot(PortalSourceShadowDepth image, Capture capture, long generation, long now) {
            this.image = image;
            camera = capture.camera;
            modelView = new Matrix4f(capture.modelView);
            projection = new Matrix4f(capture.projection);
            inverseProjectionModelView = new Matrix4f(projection).mul(modelView).invert();
            Vector3f direction = new Matrix4f(modelView).invert().transformDirection(new Vector3f(0, 0, 1)).normalize();
            towardLight = new Vec3(direction.x, direction.y, direction.z);
            shadowDistance = capture.shadowDistance;
            distanceRenderMultiplier = capture.distanceRenderMultiplier;
            sourceSunAngle = capture.sourceSunAngle;
            sourceGameTime = capture.gameTime; sourceDayTime = capture.dayTime;
            this.generation = generation; capturedNanos = now;
        }

        public int texture() { return available ? image.texture() : 0; }
        public int resolution() { return image.resolution(); }
        public Vec3 camera() { return camera; }
        public Vec3 towardLight() { return towardLight; }
        public Matrix4f modelView() { return new Matrix4f(modelView); }
        public Matrix4f projection() { return new Matrix4f(projection); }
        public Matrix4f inverseProjectionModelView() { return new Matrix4f(inverseProjectionModelView); }
        public float shadowDistance() { return shadowDistance; }
        public float distanceRenderMultiplier() { return distanceRenderMultiplier; }
        public float sourceSunAngle() { return sourceSunAngle; }
        public long sourceGameTime() { return sourceGameTime; }
        public long sourceDayTime() { return sourceDayTime; }
        public long generation() { return generation; }
        public long capturedNanos() { return capturedNanos; }

        boolean fresh(long gameTime, long dayTime, long now) {
            // Detect a clock command independently of a stopped daylight cycle. Exact world
            // identity is checked by Store; a similarly named replacement world is not enough.
            return texture() != 0 && now >= capturedNanos && now - capturedNanos <= MAX_AGE_NANOS
                && gameTime >= sourceGameTime && differenceWithin(gameTime, sourceGameTime, 40)
                && differenceWithin(dayTime, sourceDayTime, 40);
        }
        private static boolean differenceWithin(long a, long b, long maximum) {
            try { long delta = Math.subtractExact(a, b); return delta >= -maximum && delta <= maximum; }
            catch (ArithmeticException overflow) { return false; }
        }
        private void close() { available = false; image.close(); }
        private PortalSourceShadowDepth retire() { available = false; return image; }
    }

    record Capture(int depthTexture, int resolution, Vec3 camera, Matrix4fc modelView, Matrix4fc projection,
                   float shadowDistance, float distanceRenderMultiplier, float sourceSunAngle,
                   long gameTime, long dayTime) {
        boolean valid() {
            if (depthTexture <= 0 || resolution < 1 || resolution > MAX_RESOLUTION || camera == null
                || !Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)
                || modelView == null || projection == null || !modelView.isFinite() || !projection.isFinite()
                || !Float.isFinite(shadowDistance) || shadowDistance <= 0 || shadowDistance > 8192
                || !Float.isFinite(distanceRenderMultiplier) || distanceRenderMultiplier < -1 || distanceRenderMultiplier > 64
                || !Float.isFinite(sourceSunAngle) || sourceSunAngle < 0 || sourceSunAngle > 1) return false;
            // This adapter's inverse radial warp describes an orthographic shadow map only.
            if (Math.abs(projection.m03()) > 1e-6 || Math.abs(projection.m13()) > 1e-6
                || Math.abs(projection.m23()) > 1e-6 || Math.abs(projection.m33() - 1) > 1e-6) return false;
            Matrix4f transform = new Matrix4f(projection).mul(modelView);
            return Float.isFinite(transform.determinant()) && transform.determinant() != 0
                && new Matrix4f(transform).invert().isFinite() && new Matrix4f(modelView).invert().isFinite();
        }
    }

    /** Identity-owned LRU, also exercised through production GL storage without a game world. */
    static final class Store {
        private static final class Entry {
            final Object pipeline;
            final Snapshot snapshot;
            long used;
            Entry(Object pipeline, Snapshot snapshot, long used) { this.pipeline = pipeline; this.snapshot = snapshot; this.used = used; }
        }
        private final int capacity;
        private final long byteLimit;
        private final IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();
        private final ArrayList<PortalSourceShadowDepth> spare = new ArrayList<>();
        private long sequence, generation, bytes;

        Store(int capacity, long byteLimit) {
            if (capacity < 1 || capacity > MAX_WORLDS || byteLimit < 4 || byteLimit > MAX_BYTES)
                throw new IllegalArgumentException("Invalid source shadow storage bound");
            this.capacity = capacity; this.byteLimit = byteLimit;
        }

        @Nullable Snapshot get(Object world, long gameTime, long dayTime, long now) {
            Entry entry = entries.get(world);
            if (entry == null || !entry.snapshot.fresh(gameTime, dayTime, now)) return null;
            entry.used = ++sequence; return entry.snapshot;
        }

        @Nullable Snapshot capture(Object world, Object pipeline, Capture capture, long now) {
            if (world == null || pipeline == null) return null;
            Entry current = entries.get(world);
            if (current != null && current.pipeline != pipeline) remove(world);
            if (capture == null || !capture.valid()) return null;
            PortalSourceShadowDepth.Spec spec = PortalSourceShadowDepth.inspect(capture.depthTexture, capture.resolution);
            if (spec == null || spec.bytes() > byteLimit) return null;
            if (!entries.containsKey(world) && entries.size() >= capacity) remove(oldest(null));
            PortalSourceShadowDepth image = null;
            for (int i = 0; i < spare.size(); i++) if (spare.get(i).matches(spec)) { image = spare.remove(i); break; }
            current = entries.get(world);
            if (image == null && current != null && current.snapshot.image.matches(spec)
                && (bytes + spec.bytes() > byteLimit || entries.size() + spare.size() >= MAX_WORLDS)) {
                // A native 4096 map can consume the whole budget. Retire its publication
                // before overwriting, rather than delete/reallocate its storage every frame.
                // Readers borrow snapshots only until the next capture on this same thread.
                entries.remove(world);
                image = current.snapshot.retire();
            }
            if (image == null) {
                // Include reusable images and replacement peak in the budget. The ordinary
                // 2048 source alternates two 16 MiB images without per-frame texture churn.
                while (bytes + spec.bytes() > byteLimit || entries.size() + spare.size() >= MAX_WORLDS) {
                    if (!spare.isEmpty()) dispose(spare.removeLast());
                    else if (!entries.isEmpty()) remove(oldest(world));
                    else return null;
                }
                image = PortalSourceShadowDepth.allocate(spec); bytes += image.bytes();
            }
            boolean published = false;
            try {
                if (!image.copyFrom(capture.depthTexture)) return null;
                Snapshot result = new Snapshot(image, capture, ++generation, now);
                Entry previous = entries.remove(world);
                if (previous != null) spare.add(previous.snapshot.retire());
                entries.put(world, new Entry(pipeline, result, ++sequence));
                published = true; return result;
            } finally { if (!published) dispose(image); }
        }

        void invalidatePipeline(Object pipeline) {
            for (Object world : entries.keySet().toArray()) if (entries.get(world).pipeline == pipeline) remove(world);
            // Spare contents are never published, but need not survive a shader generation.
            clearSpare();
        }
        void retain(Collection<?> worlds) {
            for (Object world : entries.keySet().toArray()) if (worlds.stream().noneMatch(w -> w == world)) remove(world);
            if (entries.isEmpty()) clearSpare();
        }
        void clear() { for (Object world : entries.keySet().toArray()) remove(world); clearSpare(); }
        int size() { return entries.size(); }
        long bytes() { return bytes; }
        private Object oldest(Object preferred) {
            if (preferred != null && entries.containsKey(preferred)) return preferred;
            Object victim = null; long oldest = Long.MAX_VALUE;
            for (Map.Entry<Object, Entry> entry : entries.entrySet()) if (entry.getValue().used < oldest) {
                oldest = entry.getValue().used; victim = entry.getKey();
            }
            return victim;
        }
        private void dispose(PortalSourceShadowDepth image) { bytes -= image.bytes(); image.close(); }
        private void clearSpare() { spare.forEach(this::dispose); spare.clear(); }
        private void remove(Object world) {
            Entry entry = entries.remove(world);
            if (entry != null) { bytes -= entry.snapshot.image.bytes(); entry.snapshot.close(); }
        }
    }
}
