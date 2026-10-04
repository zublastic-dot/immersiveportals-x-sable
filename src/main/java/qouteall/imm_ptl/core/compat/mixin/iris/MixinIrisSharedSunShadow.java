package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.sunlight.SunlightClient;

/** Native depth-map camera follows exactly the light direction used by the adapted pack. */
@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinIrisSharedSunShadow {
    @Inject(method = "getShadowAngle", at = @At("HEAD"), cancellable = true)
    private static void ip_sharedShadowAngle(CallbackInfoReturnable<Float> cir) {
        var profile = SunlightClient.effectiveShaderProfile();
        var sample = SunlightClient.currentSourceSample();
        if (profile.isPresent() && sample.isPresent())
            cir.setReturnValue(SunlightClient.shadowAngle(profile.orElseThrow(), sample.orElseThrow().shadowDirection()));
    }
}
