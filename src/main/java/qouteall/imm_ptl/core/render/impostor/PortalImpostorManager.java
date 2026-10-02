package qouteall.imm_ptl.core.render.impostor;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import ipl.sable.client.IplClientShipPortalAnchor;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.Event;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.imm_ptl.core.render.renderer.RendererUsingFrameBuffer;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-only, session-local destination pictures. No portal entities, worlds or chunk
 * tickets are retained by an entry. Captures are opportunistic products of a real render;
 * the far draw never invokes destination rendering or loads a destination world.
 */
public final class PortalImpostorManager {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int MAX_ENTRIES = 16;
    private static final long CAPTURE_INTERVAL = 250_000_000L;
    private static final long RENEW_INTERVAL = 2_000_000_000L;
    private static final String RPC = PortalImpostorSync.class.getName() + ".RemoteCallables.";
    private static final LinkedHashMap<UUID, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<UUID, Portal> LIVE = new HashMap<>();
    private static final AtomicLong RELOAD = new AtomicLong();
    private static long resourceEpoch, nextCapture, nextSubscribe, nextReport, captures, draws, failures;
    private static Object connection, world, renderer, pipeline;
    private static String shaderPack;
    private static int windowWidth, windowHeight, frame = -1;
    private static boolean initialized, disabledForSession;

    /** Additional veto for cached faces, including those no longer backed by a tracked entity. */
    public static final class RenderEvent extends Event {
        public final PortalImpostorMetadata metadata;
        private boolean allowed = true;
        public RenderEvent(PortalImpostorMetadata metadata) { this.metadata = metadata; }
        public void deny() { allowed = false; }
        public boolean allowed() { return allowed; }
    }

    private static final class Entry {
        final UUID id, token = UUID.randomUUID();
        final PortalImpostorPolicy.State state = new PortalImpostorPolicy.State();
        PortalImpostorMetadata captured, authorized;
        PortalImpostorGpu.Frame image;
        int liveCompletedFrame = -1;
        int boundaryCaptureFrame = -1;
        long generation = -1, validUntil, nextRenew, lastUse, lastCapture;
        final long subscribedAt;
        String attachment;
        Entry(PortalImpostorMetadata value, long now) {
            id = value.portalId(); captured = value; lastUse = now; subscribedAt = now;
        }
        boolean valid(long now) {
            return authorized != null && now < validUntil && image != null && !image.isClosed()
                && captured.linkMatches(authorized);
        }
        void close() {
            if (image != null) image.close();
            image = null;
        }
    }

    private record Pose(Vec3 origin, Vec3 w, Vec3 h, double width, double height) {
        double distance(Vec3 eye) {
            Vec3 delta = eye.subtract(origin);
            double x = Math.max(-width / 2, Math.min(width / 2, delta.dot(w)));
            double y = Math.max(-height / 2, Math.min(height / 2, delta.dot(h)));
            return eye.distanceTo(origin.add(w.scale(x)).add(h.scale(y)));
        }
        boolean front(Vec3 eye) { return eye.subtract(origin).dot(w.cross(h)) > 0.01; }
    }
    private record Presentation(Pose pose, PortalImpostorPolicy.Decision decision) {}

    private PortalImpostorManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> {
            clear(); disabledForSession = false;
            connection = world = renderer = pipeline = null;
        });
        if (MC.getResourceManager() instanceof ReloadableResourceManager reloadable) {
            reloadable.registerReloadListener((ResourceManagerReloadListener) manager -> RELOAD.incrementAndGet());
        }
        PortalImpostorSync.setClientSink(new PortalImpostorSync.ClientSink() {
            @Override public void updated(UUID token, long generation, PortalImpostorMetadata value,
                                          boolean dormant, long leaseMillis) {
                Entry e = byToken(token);
                if (e == null || MC.getConnection() != connection || generation < e.generation) return;
                if (!e.id.equals(value.portalId()) || !e.captured.linkMatches(value)) {
                    remove(e); return;
                }
                e.generation = generation;
                e.authorized = value;
                e.validUntil = System.nanoTime() + Math.clamp(leaseMillis, 0, 10_000) * 1_000_000;
            }
            @Override public void invalidated(UUID token, long generation, String reason) {
                Entry e = byToken(token);
                if (e != null && generation >= e.generation) remove(e);
            }
        });
    }

    /** Called on the render thread after a shader renderer/pipeline is rebuilt. */
    public static void invalidate() { RELOAD.incrementAndGet(); }

    private static Entry byToken(UUID token) {
        for (Entry e : ENTRIES.values()) if (e.token.equals(token)) return e;
        return null;
    }

    private static boolean supported() {
        if (!IPGlobal.portalImpostors || disabledForSession || MC.level == null || MC.getConnection() == null
            || IPGlobal.maxPortalLayer == 0) return false;
        Object r = IPCGlobal.renderer;
        return r instanceof RendererUsingStencil || r instanceof RendererUsingFrameBuffer
            || r instanceof IrisPortalRenderer
            || r instanceof IrisCompatibilityPortalRenderer compatibility && !compatibility.isDebugMode;
    }

    /** Only outer passes call this; nested renderers must not swap the cache's world identity. */
    private static boolean prepare() {
        if (PortalRendering.isRendering() || IrisInterface.invoker.isRenderingShadowMap()) return false;
        if (!supported()) { if (!ENTRIES.isEmpty()) clear(); return false; }
        Object p = IrisInterface.invoker.isShaders() ? IrisInterface.invoker.getPipeline(MC.levelRenderer) : null;
        String pack = IrisInterface.invoker.getShaderpackName();
        RenderTarget target = MC.getMainRenderTarget();
        long epoch = RELOAD.get();
        if (connection != MC.getConnection() || world != MC.level || renderer != IPCGlobal.renderer
            || pipeline != p || !Objects.equals(shaderPack, pack) || resourceEpoch != epoch
            || windowWidth != target.viewWidth || windowHeight != target.viewHeight) {
            clear();
            connection = MC.getConnection(); world = MC.level; renderer = IPCGlobal.renderer;
            pipeline = p; shaderPack = pack; resourceEpoch = epoch;
            windowWidth = target.viewWidth; windowHeight = target.viewHeight;
        }
        if (frame != RenderStates.frameIndex) {
            frame = RenderStates.frameIndex;
            LIVE.clear();
            for (Entity entity : MC.level.entitiesForRendering())
                if (entity instanceof Portal portal && !portal.isRemoved()) LIVE.put(portal.getUUID(), portal);
            long now = System.nanoTime();
            for (Entry e : List.copyOf(ENTRIES.values())) {
                if (e.authorized == null && now - e.subscribedAt > 10_000_000_000L) {
                    fail(new IllegalStateException("Server did not acknowledge the portal image subscription"));
                    return false;
                }
                if (e.authorized != null && now > e.validUntil) { remove(e); continue; }
                if (now >= e.nextRenew) {
                    McRemoteProcedureCallClient.tellServerToInvoke(RPC + "renew", e.token);
                    e.nextRenew = now + RENEW_INTERVAL;
                }
            }
        }
        return true;
    }

    public static boolean needsRendering() {
        return prepare() && ENTRIES.values().stream().anyMatch(e -> e.valid(System.nanoTime()));
    }

    private static boolean eligible(Portal p) {
        return !p.isRemoved() && p.isVisible() && !p.isGlobalPortal && !(p instanceof Mirror)
            && !p.isFuseView() && p.isPortalValid() && p.getPortalShape() instanceof RectangularPortalShape;
    }

    public static boolean useCachedInsteadOfLive(Portal portal, double distance, double liveRange) {
        if (!prepare() || !eligible(portal)) return false;
        Entry e = ENTRIES.get(portal.getUUID());
        if (e == null) return false;
        long now = System.nanoTime();
        if (!e.captured.linkMatches(PortalImpostorMetadata.fromPortal(portal))) { remove(e); return false; }
        double cutoff = PortalImpostorPolicy.liveCutoff(IPGlobal.portalImpostorLiveDistance, liveRange);
        boolean previouslyFar = e.state.isFar();
        var presentation = presentation(e, now, CHelper.getCurrentCameraPos(), cutoff);
        if (presentation != null && !presentation.decision.renderLive() && !previouslyFar && distance <= liveRange) {
            // One last real view at the cutoff. The capture callback bypasses the
            // normal refresh throttle; if it is occluded, retain the last valid image.
            e.boundaryCaptureFrame = RenderStates.frameIndex;
            return false;
        }
        return presentation != null && !presentation.decision.renderLive();
    }

    /** Receives already-completed destination color, with source stencil coverage when shared. */
    public static void capture(Portal portal, Matrix4f modelView, Matrix4f projection,
                               RenderTarget colorSource, RenderTarget coverageSource, int stencilReference) {
        if (!prepare() || !eligible(portal)) return;
        long now = System.nanoTime();
        Entry existing = ENTRIES.get(portal.getUUID());
        boolean reentry = existing != null && existing.state.decide(
            portal.getDistanceToNearestPointInPortal(CHelper.getCurrentCameraPos()),
            PortalImpostorPolicy.liveCutoff(IPGlobal.portalImpostorLiveDistance, PortalRenderer.getRenderRange()),
            existing.valid(now), now).drawCached() && !existing.state.isFar();
        if (existing != null) existing.liveCompletedFrame = RenderStates.frameIndex;
        if (reentry) {
            // This callback itself proves a fresh live destination render completed.
            // A new rectangular texture is not required: a near/partly offscreen
            // aperture cannot yield one, but must still hand control back to live.
            existing.state.liveRendered(now);
            return;
        }
        boolean boundary = existing != null && existing.boundaryCaptureFrame == RenderStates.frameIndex;
        if (now < nextCapture && !boundary) return;
        if (!boundary && existing != null && now - existing.lastCapture
            < CAPTURE_INTERVAL * Math.max(2, ENTRIES.size() + 1)) return;
        if (existing == null && now < nextSubscribe) return;
        var projected = PortalImpostorProjection.create(modelView, projection,
            portal.getOriginPos().subtract(CHelper.getCurrentCameraPos()), portal.getAxisW(), portal.getAxisH(),
            portal.getWidth(), portal.getHeight());
        if (projected.isEmpty() || !projected.get().fullyVisible()) return;
        nextCapture = now + CAPTURE_INTERVAL;
        PortalImpostorGpu.Frame acquired = null;
        try {
            var image = PortalImpostorGpu.capture(projected.get(), colorSource.getColorTextureId(),
                coverageSource.frameBufferId, stencilReference, colorSource.viewWidth, colorSource.viewHeight,
                PortalImpostorPolicy.resolution(IPGlobal.portalImpostorResolution));
            if (image == null) return;
            if (image.coverageFraction() < .995) { image.close(); return; }
            acquired = image;
            var metadata = PortalImpostorMetadata.fromPortal(portal);
            Entry e = existing;
            if (e != null && !e.captured.linkMatches(metadata)) { remove(e); e = null; }
            if (e == null) {
                while (ENTRIES.size() >= MAX_ENTRIES) {
                    Entry oldest = Collections.min(ENTRIES.values(), Comparator.comparingLong(x -> x.lastUse));
                    remove(oldest);
                }
                e = new Entry(metadata, now);
                var anchor = IplClientShipPortalAnchor.resolveImpostorPose(e.id, portal.getOriginDim());
                if (metadata.sourceAnchor() != null) e.attachment = anchor.attachmentFingerprint();
                e.image = image;
                acquired = null;
                ENTRIES.put(e.id, e);
                e.nextRenew = now + RENEW_INTERVAL;
                nextSubscribe = now + 300_000_000L;
                McRemoteProcedureCallClient.tellServerToInvoke(RPC + "subscribe", metadata.sourceDimension(), e.id, e.token);
                LOG.info("[PortalImpostor] captured {} {}px; awaiting authoritative metadata", e.id, image.width());
            } else {
                e.captured = metadata;
                if (e.image != null) e.image.close();
                e.image = image;
                acquired = null;
            }
            e.lastCapture = now;
            captures++;
        } catch (RuntimeException failure) {
            if (acquired != null) acquired.close();
            fail(failure);
        }
    }

    public static boolean renderCached(Matrix4f modelView, Matrix4f projection,
                                       RenderTarget target, int stencilReference) {
        if (!prepare()) return false;
        boolean rendered = false;
        Vec3 eye = CHelper.getCurrentCameraPos();
        long now = System.nanoTime();
        double cutoff = PortalImpostorPolicy.liveCutoff(IPGlobal.portalImpostorLiveDistance, PortalRenderer.getRenderRange());
        try {
            // Actual aperture depth handles mutually overlapping cached faces and source terrain.
            for (Entry e : List.copyOf(ENTRIES.values())) {
                var presentation = presentation(e, now, eye, cutoff);
                if (presentation == null) continue;
                Pose pose = presentation.pose;
                var decision = presentation.decision;
                if (!decision.drawCached()) continue;
                var projected = PortalImpostorProjection.create(modelView, projection,
                    pose.origin.subtract(eye), pose.w, pose.h, pose.width, pose.height);
                if (projected.isEmpty()) continue;
                int reference = IPCGlobal.renderer instanceof IrisPortalRenderer
                    && e.liveCompletedFrame == RenderStates.frameIndex ? -1 : stencilReference;
                if (PortalImpostorGpu.draw(e.image, projected.get(), target.frameBufferId, target.viewWidth,
                    target.viewHeight, reference, decision.cachedAlpha())) {
                    rendered = true; draws++; e.lastUse = now;
                }
            }
        } catch (RuntimeException failure) { fail(failure); }
        if (rendered && now >= nextReport) {
            nextReport = now + 10_000_000_000L;
            LOG.info("[PortalImpostor] entries={} captures={} cachedDraws={} livePortalsThisFrame={}",
                ENTRIES.size(), captures, draws, RenderStates.getRenderedPortalNum());
        }
        return rendered;
    }

    /** Live suppression and cached composition share every eligibility check. */
    private static Presentation presentation(Entry e, long now, Vec3 eye, double cutoff) {
        if (!e.valid(now) || !NeoForge.EVENT_BUS.post(new RenderEvent(e.authorized)).allowed()) {
            e.state.reset(); return null;
        }
        Pose pose = resolvePose(e);
        if (pose == null || !pose.front(eye) || pose.distance(eye) > IPGlobal.portalImpostorMaxDistance) {
            e.state.reset(); return null;
        }
        return new Presentation(pose, e.state.decide(pose.distance(eye), cutoff, true, now));
    }

    private static Pose resolvePose(Entry e) {
        var anchor = IplClientShipPortalAnchor.resolveImpostorPose(e.id, MC.level.dimension());
        if (e.authorized.sourceAnchor() != null) {
            if (anchor.status() != IplClientShipPortalAnchor.Status.RESOLVED) return null;
            if (e.attachment != null && !e.attachment.equals(anchor.attachmentFingerprint())) { remove(e); return null; }
            e.attachment = anchor.attachmentFingerprint();
        }
        Portal live = LIVE.get(e.id);
        if (live != null) {
            if (!eligible(live) || !e.captured.linkMatches(PortalImpostorMetadata.fromPortal(live))) {
                remove(e); return null;
            }
            if (!NeoForge.EVENT_BUS.post(new PortalRenderer.PortalRenderingPredicateEvent(live)).canRender()) return null;
            return new Pose(live.getOriginPos(), live.getAxisW(), live.getAxisH(), live.getWidth(), live.getHeight());
        }
        PortalImpostorMetadata m = e.authorized;
        if (!MC.level.dimension().location().toString().equals(m.sourceDimension())) return null;
        if (m.sourceAnchor() != null) {
            return new Pose(anchor.origin(), anchor.axisW(), anchor.axisH(), m.width(), m.height());
        }
        if (anchor.status() != IplClientShipPortalAnchor.Status.STATIC) return null;
        return new Pose(m.origin(), m.axisW(), m.axisH(), m.width(), m.height());
    }

    private static void remove(Entry e) {
        if (!ENTRIES.remove(e.id, e)) return;
        e.close();
        if (MC.getConnection() != null && MC.getConnection() == connection)
            McRemoteProcedureCallClient.tellServerToInvoke(RPC + "unsubscribe", e.token);
    }

    public static void clear() {
        RenderSystem.assertOnRenderThreadOrInit();
        for (Entry e : List.copyOf(ENTRIES.values())) remove(e);
        LIVE.clear(); frame = -1;
        nextCapture = nextSubscribe = 0;
        PortalImpostorGpu.clear();
    }

    private static void fail(RuntimeException failure) {
        disabledForSession = true; failures++;
        clear();
        LOG.warn("IP/Sable: portal image cache disabled for this session; retaining live portal rendering", failure);
    }

    /** Read-only runtime evidence; counts describe cache work, not world/chunk loading. */
    public static Map<String, Object> diagnostics() {
        return Map.of("entries", ENTRIES.size(), "captures", captures, "draws", draws,
            "failures", failures, "disabledForSession", disabledForSession,
            "liveDistance", IPGlobal.portalImpostorLiveDistance, "maximumDistance", IPGlobal.portalImpostorMaxDistance);
    }
}
