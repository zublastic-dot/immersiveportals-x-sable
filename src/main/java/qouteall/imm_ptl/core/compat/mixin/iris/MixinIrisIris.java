package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Iris.class, remap = false)
public class MixinIrisIris {
    @org.spongepowered.asm.mixin.injection.Inject(method = "destroyEverything", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private static void ip_clearPortalShaderState(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter.clear();
        qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalShaderPackAdapter.clear();
        qouteall.imm_ptl.core.lighting.PortalSourceShadow.clear();
    }
    // test
    // only overworld
//    @Inject(
//        method = "getCurrentDimension", at = @At("HEAD"), cancellable = true
//    )
//    private static void onGetCurrentDimension(CallbackInfoReturnable<DimensionId> cir) {
//        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer) {
//            cir.setReturnValue(DimensionId.OVERWORLD);
//        }
//    }

//    // it cannot recognize sodium from jitpack
//    @Inject(
//        method = "isSodiumInvalid", at = @At("HEAD"), cancellable = true
//    )
//    private static void onIsSodiumInvalid(CallbackInfoReturnable<Boolean> cir) {
//        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
//            cir.setReturnValue(false);
//        }
//    }
}
