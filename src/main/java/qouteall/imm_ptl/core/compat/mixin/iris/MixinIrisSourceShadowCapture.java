package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalShaderLighting;
import qouteall.imm_ptl.core.lighting.PortalSourceShadow;

/** Captures a completed real source pass before a recursive destination pass can replace it. */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class MixinIrisSourceShadowCapture {
    @Shadow @Final private IrisRenderingPipeline pipeline;
    @Shadow @Final private ShadowRenderTargets targets;
    @Shadow @Final private float halfPlaneLength;
    @Shadow @Final private float renderDistanceMultiplier;

    @Inject(method = "createShadowFrustum", at = @At("RETURN"), cancellable = true)
    private void ip_sourceCastersIndependentOfView(float multiplier,
            net.irisshaders.iris.shadows.frustum.FrustumHolder previous,
            CallbackInfoReturnable<net.irisshaders.iris.shadows.frustum.FrustumHolder> cir) {
        if (!qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy.isRendering()) return;
        cir.setReturnValue(qouteall.imm_ptl.core.compat.iris_compatibility.IrisSourceShadowFrustum.select(
            cir.getReturnValue(), halfPlaneLength, multiplier,
            net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance,
            Minecraft.getInstance().options.getEffectiveRenderDistance()));
    }

    // TAIL deliberately excludes the earlier return for disabled shadow distance.
    // A pipeline RETURN hook would otherwise publish last frame's matrices as fresh.
    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void ip_captureSourceShadow(LevelRendererAccessor renderer, Camera camera, CallbackInfo ci) {
        ClientLevel source = renderer.getLevel();
        if (targets == null || ShadowRenderer.ACTIVE
            || source == null || source != Minecraft.getInstance().level
            || !PortalShaderLighting.supported() || !PortalShaderLighting.usesSource(source)
            || ShadowRenderer.MODELVIEW == null || ShadowRenderer.PROJECTION == null) return;
        var position = CameraUniforms.getUnshiftedCameraPosition();
        float skyAngle = source.getTimeOfDay(CapturedRenderingState.INSTANCE.getTickDelta());
        float sunAngle = skyAngle < .75f ? skyAngle + .25f : skyAngle - .75f;
        PortalSourceShadow.capture(source, pipeline, targets.getDepthTextureNoTranslucents().getTextureId(),
            targets.getResolution(), new Vec3(position.x, position.y, position.z),
            ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION,
            halfPlaneLength, renderDistanceMultiplier, sunAngle);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void ip_releaseSourceShadow(CallbackInfo ci) { PortalSourceShadow.invalidatePipeline(pipeline); }
}
