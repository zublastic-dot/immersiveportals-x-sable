package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.PortalShaderBindingSlot;
import qouteall.imm_ptl.core.lighting.PortalShaderGpu;

@Mixin(value = ExtendedShader.class, remap = false)
public class MixinIrisPortalSunExtendedShader {
    @Unique private final PortalShaderBindingSlot ip_sun = new PortalShaderBindingSlot();

    @Inject(method = "apply", at = @At("HEAD"))
    private void ip_beginSun(CallbackInfo ci) { ip_sun.begin(); }

    @Inject(method = "clear", at = @At("HEAD"))
    private void ip_closeSun(CallbackInfo ci) { ip_sun.close(); }

    @Inject(method = "apply", at = @At("RETURN"))
    private void ip_bindSun(CallbackInfo ci) { ip_sun.bind(PortalShaderGpu::bind); }
}
