package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.sunlight.SunlightClient;

@Mixin(value = CelestialUniforms.class, remap = false)
public class MixinIrisSharedSunCelestial {
    @Inject(method = "getCelestialPositionInWorldSpace", at = @At("HEAD"), cancellable = true)
    private void ip_sharedWorldPosition(float height, CallbackInfoReturnable<Vector4f> cir) {
        SunlightClient.currentSourceSample().ifPresent(sample -> {
            var d = sample.solarDirection();
            cir.setReturnValue(new Vector4f((float)d.x * height, (float)d.y * height, (float)d.z * height, 0));
        });
    }

    @Inject(method = "getCelestialPosition", at = @At("HEAD"), cancellable = true)
    private void ip_sharedViewPosition(float height, CallbackInfoReturnable<Vector4f> cir) {
        SunlightClient.currentSourceSample().ifPresent(sample -> {
            var d = sample.solarDirection();
            var position = new Vector4f((float)d.x * height, (float)d.y * height, (float)d.z * height, 0);
            CapturedRenderingState.INSTANCE.getGbufferModelView().transform(position);
            cir.setReturnValue(position);
        });
    }

    @Inject(method = "isDay", at = @At("HEAD"), cancellable = true)
    private static void ip_sharedShadowBody(CallbackInfoReturnable<Boolean> cir) {
        SunlightClient.currentSourceSample().ifPresent(sample ->
            cir.setReturnValue(sample.shadowDirection().dot(sample.solarDirection()) > 0));
    }

    @Inject(method = "getShadowAngle", at = @At("HEAD"), cancellable = true)
    private static void ip_sharedShadowUniform(CallbackInfoReturnable<Float> cir) {
        var profile = SunlightClient.effectiveShaderProfile();
        var sample = SunlightClient.currentSourceSample();
        if (profile.isPresent() && sample.isPresent())
            cir.setReturnValue(SunlightClient.shadowAngle(profile.orElseThrow(), sample.orElseThrow().shadowDirection()));
    }
}
