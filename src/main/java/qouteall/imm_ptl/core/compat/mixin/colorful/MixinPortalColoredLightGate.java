package qouteall.imm_ptl.core.compat.mixin.colorful;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalColoredLighting;
import qouteall.imm_ptl.core.lighting.PortalNativeColoredLighting;

/** Optional native RGB vertex sampling; does not modify Colorful's propagation storage. */
@Pseudo
@Mixin(targets = "dev.colorfullighting.compat.ColorfulLightGate", remap = false)
public abstract class MixinPortalColoredLightGate {
    @Inject(method = "trySampleColorful(Ljava/lang/Object;DDD)Lme/erykczy/colorfullighting/common/util/ColorRGB8;",
        at = @At("RETURN"), cancellable = true, remap = false)
    private static void ip_portalRgb(Object level, double x, double y, double z, CallbackInfoReturnable<Object> cir) {
        Object original = cir.getReturnValue();
        Object local = PortalNativeColoredLighting.sample(level, x, y, z, original);
        Object merged = PortalColoredLighting.merge(level, x, y, z, local);
        if (merged != original) cir.setReturnValue(merged);
    }
}
