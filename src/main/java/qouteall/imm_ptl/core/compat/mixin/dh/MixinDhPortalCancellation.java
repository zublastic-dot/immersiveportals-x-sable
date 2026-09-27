package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractImmersivePortalsAccessor$BeforeRenderEvent", remap = false)
public class MixinDhPortalCancellation {
    @Inject(method = "beforeRender", at = @At("HEAD"), cancellable = true)
    private void ip_allowPreparedView(DhApiCancelableEventParam<DhApiRenderParam> event, CallbackInfo ci) {
        // Skip only DH's blanket IP veto. Never clear cancellation by another listener.
        if (DhPortalRendering.isSupportedPass()) ci.cancel();
    }
}
