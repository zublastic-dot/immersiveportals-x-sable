package qouteall.imm_ptl.core.compat.mixin.colorful;

import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalColoredShaderCarrier;

/** Colorful already owns RGB hue; only virtual light's missing scalar brightness is supplied here. */
@Pseudo
@Mixin(targets = "dev.colorfullighting.compat.iris.IrisShaderCompat", remap = false)
public abstract class MixinPortalColoredIrisCarrier {
    @Inject(method = "patchFragment(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"),
        cancellable = true, remap = false)
    private static void ip_carrier(String source, CallbackInfoReturnable<String> cir) {
        if (Iris.getIrisConfig() == null) return;
        String original = cir.getReturnValue();
        String patched = PortalColoredShaderCarrier.patch(Iris.getIrisConfig().getShaderPackName().orElse(""), original);
        if (patched != original) cir.setReturnValue(patched);
    }
}
