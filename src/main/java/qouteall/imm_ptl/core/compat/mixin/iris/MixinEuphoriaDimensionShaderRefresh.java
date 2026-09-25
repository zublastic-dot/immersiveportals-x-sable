package qouteall.imm_ptl.core.compat.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.EuphoriaPortalPipelines;

@Pseudo
@Mixin(targets = "com.euphoriapatches.euphoria_patcher.integration.iris.DimensionShaderRefresh", remap = false)
public class MixinEuphoriaDimensionShaderRefresh {
    @Inject(method = {"beforePreparePipeline", "processPending", "onSetLevelReturn"},
        at = @At("HEAD"), cancellable = true, require = 3)
    private static void ip_useDimensionPipelines(CallbackInfo ci) {
        if (EuphoriaPortalPipelines.isManaged()) {
            ci.cancel();
        }
    }

    @Inject(method = "onGenuineReload", at = @At("RETURN"))
    private static void ip_invalidateOnShaderPackReload(String dimension, CallbackInfo ci) {
        EuphoriaPortalPipelines.clear();
    }
}
