package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer_neoforge", remap = false)
public class MixinDhAntiAliasing {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ip_noCrossDimensionHistory(RenderParams params, CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            DhPortalTaa.render(params);
            ci.cancel();
        }
    }
    @Inject(method = "free", at = @At("HEAD"))
    private void ip_freePortalHistory(CallbackInfo ci) { DhPortalTaa.clear(); }
}
