package qouteall.imm_ptl.core.compat.mixin.colorful;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.lighting.PortalNativeColoredLighting;

/** Resource/editor color changes invalidate remote fields without modifying Colorful's native engine. */
@Pseudo
@Mixin(targets="me.erykczy.colorfullighting.common.Config",remap=false)
public abstract class MixinPortalColoredLightConfig {
    @Inject(method={"setColorEmitters(Ljava/util/HashMap;)V","setColorFilters(Ljava/util/HashMap;)V",
        "setStateColorEmitters(Ljava/util/HashMap;)V","setStateColorFilters(Ljava/util/HashMap;)V",
        "setUserBlockOverrides(Ljava/util/Map;)V"},at=@At("RETURN"),require=5,remap=false)
    private static void ip_nativePortalColorsChanged(CallbackInfo ci) {
        PortalNativeColoredLighting.configChanged();
    }
}
