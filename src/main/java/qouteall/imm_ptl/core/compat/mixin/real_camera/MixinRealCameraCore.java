package qouteall.imm_ptl.core.compat.mixin.real_camera;

import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/** Keep the player-bound Real Camera cache out of IP's independently transformed views. */
@Pseudo
@Mixin(targets = "com.xtracr.realcamera.RealCameraCore", remap = false)
public class MixinRealCameraCore {
    @Unique
    private static boolean ip_reportedRealCameraIsolation;

    @Inject(method = "isActive()Z", at = @At("HEAD"), cancellable = true)
    private static void ip_keepPortalCamera(CallbackInfoReturnable<Boolean> cir) {
        // isRendering() also delegates to isActive(), so this excludes Real Camera's
        // first-person body pass in the destination without hiding normal IP entities.
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "initialize(Lnet/minecraft/client/Minecraft;)V",
        at = @At("HEAD"), cancellable = true)
    private static void ip_preserveMainCameraState(CallbackInfo ci) {
        // Real Camera's GameRenderer hook runs recursively. Do not overwrite its
        // main-view flags/cache with the destination world. The subsequent isActive
        // query above also prevents computeCamera and the Camera.setup RETURN hook.
        if (WorldRenderInfo.isRendering()) {
            ci.cancel();
            if (!ip_reportedRealCameraIsolation) {
                ip_reportedRealCameraIsolation = true;
                LogUtils.getLogger().info("IP/Sable Real Camera: isolating nested world views; main-view camera state preserved");
            }
        }
    }
}
