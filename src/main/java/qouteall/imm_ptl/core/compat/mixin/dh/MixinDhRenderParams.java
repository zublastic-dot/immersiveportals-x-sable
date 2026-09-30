package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;

@Pseudo
@Mixin(value = RenderParams.class, remap = false)
public class MixinDhRenderParams {
    @Inject(method = "update(Lcom/seibel/distanthorizons/api/enums/rendering/EDhApiRenderPass;Lcom/seibel/distanthorizons/core/api/internal/rendering/DhRenderState;)V", at = @At("TAIL"))
    private void ip_prepareView(EDhApiRenderPass pass, DhRenderState state, CallbackInfo ci) {
        DhPortalRendering.prepare((RenderParams)(Object)this);
        qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa.prepareMain((RenderParams)(Object)this);
    }
}
