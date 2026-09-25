package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.EuphoriaPortalPipelines;

// Run before Euphoria's priority-1000 preparePipeline HEAD injection.
@Mixin(value = PipelineManager.class, remap = false, priority = 1100)
public class MixinIrisEuphoriaPipelineManager {
    @Inject(method = "preparePipeline", at = @At("HEAD"))
    private void ip_selectDimensionPack(NamespacedId dimension,
                                        CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        EuphoriaPortalPipelines.prepare(dimension);
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void ip_releaseDimensionPacks(CallbackInfo ci) {
        EuphoriaPortalPipelines.clear();
    }
}
