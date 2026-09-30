package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon_neoforge", remap = false)
public class MixinDhVanillaFog {
    @WrapOperation(method = "cancelFog", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor;isRenderingPortal()Z"))
    private static boolean ip_useDestinationFogPolicy(IImmersivePortalsAccessor accessor, Operation<Boolean> original) {
        // DH's unsupported-portal fallback forces distance fog back on, painting
        // vanilla terrain sky-coloured before it meets the LODs. Only remove that
        // veto; keep DH's setting, fluid, blindness and special-fog decisions.
        return original.call(accessor) && !PortalRendering.isRendering();
    }
}
