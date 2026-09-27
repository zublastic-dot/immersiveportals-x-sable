package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.LodRenderer", remap = false)
public class MixinDhLodRenderer {
    @Inject(method = {"render", "renderDeferred"}, at = @At("HEAD"), cancellable = true)
    private void ip_onlyPreparedPasses(RenderParams params, IProfilerWrapper profiler, CallbackInfo ci) {
        if (PortalRendering.isRendering() && !DhPortalRendering.isSupportedPass()) ci.cancel();
    }

    @Inject(method = {"render", "renderDeferred"}, at = @At("RETURN"))
    private void ip_reportView(RenderParams params, IProfilerWrapper profiler, CallbackInfo ci) {
        DhPortalRendering.report(params);
    }
}
