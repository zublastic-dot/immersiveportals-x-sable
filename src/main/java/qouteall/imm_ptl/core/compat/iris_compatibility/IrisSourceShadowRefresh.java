package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.lighting.PortalShaderLighting;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshGlState;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy;
import qouteall.imm_ptl.core.lighting.PortalSourceShadow;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.RendererDummy;

import java.util.IdentityHashMap;
import java.util.UUID;

/**
 * Keeps native source shadows alive while the receiver is visible and its portal is behind the
 * camera. This runs before the primary render, never recursively inside an Iris pipeline. The
 * offscreen world uses only already loaded client geometry; no visibility or chunk tickets change.
 */
public final class IrisSourceShadowRefresh {
    private static final RendererDummy NO_PORTALS = new RendererDummy();
    private static final UUID CONTEXT = UUID.fromString("82d2c819-51cc-48ef-bad3-892cdcbebd7e");
    private static final IdentityHashMap<ClientLevel, Long> nextAttempts = new IdentityHashMap<>();
    private static TextureTarget target;
    private static boolean failed, warnedSize;
    private static int diagnostics;
    private static String lastGate;
    private static int gateDiagnostics;

    private IrisSourceShadowRefresh() {}

    public static void beforePrimaryRender() {
        Minecraft mc = Minecraft.getInstance();
        if (failed || PortalSourceRefreshPolicy.isRendering() || WorldRenderInfo.isRendering()
            || !PortalShaderLighting.supported() || mc.level == null || mc.player == null
            || mc.getCameraEntity() == null || !ClientWorldLoader.getIsInitialized()
            || CapturedRenderingState.INSTANCE.getGbufferProjection() == null
            || CapturedRenderingState.INSTANCE.getGbufferModelView() == null) return;
        nextAttempts.keySet().removeIf(world -> ClientWorldLoader.getClientWorlds().stream().noneMatch(loaded -> loaded == world));
        ClientLevel receiver = mc.level;
        Vec3 eye = mc.getCameraEntity().getEyePosition(RenderStates.getPartialTick());
        PortalShaderLighting.Region selected = null;
        double nearest = Double.POSITIVE_INFINITY;
        long now = System.nanoTime();
        var regions = PortalShaderLighting.regions(receiver);
        for (PortalShaderLighting.Region region : regions) {
            ClientLevel source = region.aperture().source();
            if (source == receiver || ClientWorldLoader.getClientWorlds().stream().noneMatch(world -> world == source)
                || now < nextAttempts.getOrDefault(source, Long.MIN_VALUE)) continue;
            var snapshot = PortalSourceShadow.get(source);
            if (!PortalSourceRefreshPolicy.due(now, snapshot == null ? -1 : snapshot.capturedNanos(),
                nextAttempts.getOrDefault(source, Long.MIN_VALUE))) continue;
            double distance = PortalSourceRefreshPolicy.distanceSquared(eye, region.ambientMin(), region.ambientMax());
            if (distance <= 16 * 16 && distance < nearest) { nearest = distance; selected = region; }
        }
        if (selected == null) { gate(regions.isEmpty() ? "no-receiver-region" : "no-due-nearby-source", receiver); return; }
        gate("refresh-requested", receiver);
        RenderTarget primary = mc.getMainRenderTarget();
        if (!PortalSourceRefreshPolicy.supportedSize(primary.width, primary.height)) {
            if (!warnedSize) { warnedSize = true; LogUtils.getLogger().warn("[IP shader light] auxiliary source refresh unavailable: main target exceeds bounded pixel budget"); }
            return;
        }
        var aperture = selected.aperture();
        ClientLevel source = aperture.source();
        nextAttempts.put(source, now + PortalSourceRefreshPolicy.REFRESH_NANOS);
        // The main position still describes the preceding frame (possibly the opposite world
        // immediately after crossing). Retain its eye-height interpolation, then calculate the
        // current native third/first-person perspective only when a source pass is actually due.
        Camera currentCamera = new Camera();
        IECamera previousCamera = (IECamera) mc.gameRenderer.getMainCamera();
        ((IECamera) currentCamera).ip_setCameraY(previousCamera.ip_getCameraY(), previousCamera.ip_getLastCameraY());
        currentCamera.setup(receiver, mc.getCameraEntity(), !mc.options.getCameraType().isFirstPerson(),
            mc.options.getCameraType().isMirrored(), RenderStates.getPartialTick());
        eye = currentCamera.getPosition();
        Vec3 sourceEye = PortalSourceRefreshPolicy.sourcePoint(eye, aperture.center(), aperture.sourceCenter(),
            aperture.toSourceX(), aperture.toSourceY(), aperture.toSourceZ());
        WorldRenderInfo info = new WorldRenderInfo.Builder().setWorld(source).setCameraPos(sourceEye)
            .setCameraTransformation(PortalSourceRefreshPolicy.cameraTransform(aperture.toSourceX(), aperture.toSourceY(), aperture.toSourceZ()))
            .setOverwriteCameraTransformation(false).setDescription(CONTEXT).setDoRenderHand(false)
            .setEnableViewBobbing(false).setRenderDistance(mc.options.getEffectiveRenderDistance()).build();
        var previous = PortalSourceShadow.get(source);
        long before = previous == null ? -1 : previous.generation();
        try (var scope = PortalSourceRefreshPolicy.enter(); var gl = new PortalSourceRefreshGlState(); var state = new IrisSourceRefreshState()) {
            if (target == null || target.width != primary.width || target.height != primary.height) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget(primary.width, primary.height, true, Minecraft.ON_OSX);
            }
            var renderer = IPCGlobal.renderer;
            try {
                ((IEMinecraftClient) mc).ip_setFrameBuffer(target);
                IPCGlobal.renderer = NO_PORTALS;
                target.bindWrite(true);
                RenderStates.basicProjectionMatrix = null;
                MyGameRenderer.renderWorldNew(info, Runnable::run);
            } finally {
                IPCGlobal.renderer = renderer;
                ((IEMinecraftClient) mc).ip_setFrameBuffer(primary);
                // If native rendering threw before finalizeLevelRendering, its cached source
                // pipeline must not retain an open world phase when a visible portal uses it later.
                var currentPipeline = Iris.getPipelineManager().getPipelineNullable();
                if (currentPipeline instanceof IEIrisNewWorldRenderingPipeline nativePipeline)
                    nativePipeline.ip_setIsRenderingWorld(false);
                // Native preparePipeline also restores Euphoria's dimension pack and Iris's
                // global program/material/DH override selection. mc.level is restored first.
                if (mc.level == receiver)
                    Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());
            }
        } catch (RuntimeException failure) {
            // Do not repeatedly invoke a failing native pipeline. Full shader reload/disconnect
            // clears this guard; the strict CPU path remains the only fallback.
            failed = true;
            LogUtils.getLogger().warn("[IP shader light] auxiliary source refresh failed; disabled until shader/world reset", failure);
            return;
        }
        var after = PortalSourceShadow.get(source);
        boolean captured = after != null && after.generation() != before;
        nextAttempts.put(source, System.nanoTime() + (captured
            ? PortalSourceRefreshPolicy.REFRESH_NANOS : PortalSourceRefreshPolicy.RETRY_NANOS));
        if (diagnostics++ < 8) LogUtils.getLogger().info("[IP shader light] auxiliary source refresh {} -> {}: captured={}, generationBefore={}, generationAfter={}, durationMs={}",
            source.dimension().location(), receiver.dimension().location(), captured, before, after == null ? -1 : after.generation(), (System.nanoTime() - now) / 1_000_000);
    }

    private static void gate(String reason, ClientLevel receiver) {
        String gate = receiver.dimension().location() + ":" + reason;
        if (!gate.equals(lastGate) && gateDiagnostics++ < 12)
            LogUtils.getLogger().info("[IP shader light] auxiliary source gate {}", gate);
        lastGate = gate;
    }

    public static void clear() {
        if (target != null) { target.destroyBuffers(); target = null; }
        nextAttempts.clear(); failed = false; warnedSize = false; diagnostics = 0; lastGate = null; gateDiagnostics = 0;
    }
}
