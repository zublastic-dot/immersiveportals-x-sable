package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhRenderTraceProbe;

@Pseudo
@Mixin(value = GlDhMetaRenderer_neoforge.class, remap = false)
public class MixinDhRenderTraceMeta {
    @Inject(method = "runRenderPassSetup", at = @At("RETURN"))
    private void ip_traceSetup(RenderParams params, CallbackInfo ci) {
        DhRenderTraceProbe.sample(params, "setupComplete");
    }
}
