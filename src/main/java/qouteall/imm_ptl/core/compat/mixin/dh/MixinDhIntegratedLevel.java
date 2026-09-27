package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalLevel;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.DhClientServerLevel", remap = false)
public class MixinDhIntegratedLevel {
    @Inject(method = "getClientLevelWrapper", at = @At("HEAD"), cancellable = true)
    private void ip_ownLevelWrapper(CallbackInfoReturnable<IClientLevelWrapper> cir) {
        var view = ((DhPortalLevel)this).ip_getDhView();
        // Async LOD building must never use the other dimension's biome/block wrapper.
        if (view != null && view.level().getDhLevel() == (Object)this) cir.setReturnValue(view.level());
    }
}
