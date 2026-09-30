package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer_neoforge", remap = false)
public class MixinDhAntiAliasing {
    @Shadow private int framebufferA, framebufferB, width, height;
    @Shadow private boolean textureAIsHistory;

    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/common/render/openGl/postProcessing/antialiasing/GlDhTaaShader_neoforge;renderPrep(III)V"))
    private void ip_seedCrossingHistory(RenderParams params, CallbackInfo ci) {
        DhPortalTaa.seedMainHistory(textureAIsHistory ? framebufferA : framebufferB, width, height);
    }
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
