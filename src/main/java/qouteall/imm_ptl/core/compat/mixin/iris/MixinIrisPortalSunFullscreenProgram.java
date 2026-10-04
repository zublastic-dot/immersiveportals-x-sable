package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.PortalShaderBindingSlot;
import qouteall.imm_ptl.core.compat.iris_compatibility.PortalBloomBinding;
import qouteall.imm_ptl.core.lighting.PortalShaderGpu;

/** Iris Program.unbind is static: at most one fullscreen program can be current. */
@Mixin(value = Program.class, remap = false)
public class MixinIrisPortalSunFullscreenProgram {
    @Unique private final PortalShaderBindingSlot ip_sun = new PortalShaderBindingSlot();

    @Inject(method = "use", at = @At("HEAD"))
    private void ip_beginSun(CallbackInfo ci) { ip_sun.begin(); }

    @Inject(method = "destroyInternal", at = @At("HEAD"))
    private void ip_closePreviousSun(CallbackInfo ci) { ip_sun.close(); }

    @Inject(method = "use", at = @At("RETURN"))
    private void ip_bindSun(CallbackInfo ci) {
        ip_sun.bind(PortalShaderGpu::bind);
        PortalBloomBinding.bind();
    }

    @Inject(method = "unbind", at = @At("HEAD"))
    private static void ip_closeSun(CallbackInfo ci) { PortalShaderBindingSlot.closeCurrent(); }
}
