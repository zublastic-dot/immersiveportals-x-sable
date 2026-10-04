package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.lighting.PortalColoredLighting;

@Mixin(value = PipelineManager.class, remap = false)
abstract class MixinIrisPortalRgbLifecycle {
    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void ip_invalidateRgbCarrier(CallbackInfo ci) { PortalColoredLighting.invalidateShaders(); }
}
