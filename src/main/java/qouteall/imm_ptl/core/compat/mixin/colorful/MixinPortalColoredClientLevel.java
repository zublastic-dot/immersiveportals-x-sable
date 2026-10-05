package qouteall.imm_ptl.core.compat.mixin.colorful;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalNativeColoredLighting;

/** The native singleton must not observe the temporary world/renderer selected by a portal render pass. */
@Pseudo
@Mixin(targets="me.erykczy.colorfullighting.accessors.MinecraftWrapper",remap=false)
public abstract class MixinPortalColoredClientLevel {
    @Shadow @Final private Minecraft minecraft;
    @Inject(method="getLevel()Lme/erykczy/colorfullighting/common/accessors/LevelAccessor;",
        at=@At("RETURN"),cancellable=true,require=1,remap=false)
    private void ip_primaryColorfulLevel(CallbackInfoReturnable<Object> cir) {
        Object original=cir.getReturnValue();
        Object stable=PortalNativeColoredLighting.primaryAccessor(minecraft,original);
        if (stable!=original) cir.setReturnValue(stable);
    }
}
