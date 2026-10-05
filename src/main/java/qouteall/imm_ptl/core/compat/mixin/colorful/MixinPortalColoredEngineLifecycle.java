package qouteall.imm_ptl.core.compat.mixin.colorful;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.lighting.PortalNativeColoredLighting;

/** Hand off retained colors to native propagation precisely when its initial mesh refresh is requested. */
@Pseudo
@Mixin(targets="me.erykczy.colorfullighting.common.ColoredLightEngine",remap=false)
public abstract class MixinPortalColoredEngineLifecycle {
    @Unique private long ip_nativeWarmupGeneration;
    @Inject(method="reset()V",at=@At("HEAD"),require=1,remap=false)
    private void ip_beginNativeWarmup(CallbackInfo ci) {
        PortalNativeColoredLighting.nativeReset(this);
    }
    @Inject(method="onLightUpdate()V",at=@At("HEAD"),require=1,remap=false)
    private void ip_captureWarmupGeneration(CallbackInfo ci) {
        ip_nativeWarmupGeneration=PortalNativeColoredLighting.nativeWarmupGeneration(this);
    }
    @Inject(method="onLightUpdate()V",at=@At(value="INVOKE",
        target="Lme/erykczy/colorfullighting/common/accessors/LevelAccessor;rebuildAllSections()V",
        shift=At.Shift.BEFORE),require=1,remap=false)
    private void ip_finishNativeWarmupBeforeRemesh(CallbackInfo ci) {
        PortalNativeColoredLighting.nativeInitialLightReady(this,ip_nativeWarmupGeneration);
    }
}
