package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhNearbyLevelRetention;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge", remap = false)
public class MixinDhNearbyLevelRetention {
    @WrapOperation(method = "tickCleanup()V", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/common/wrappers/world/ClientLevelWrapper_neoforge;tryUnloadFromWorld()V"), require = 1)
    private static void ip_keepNearbyDestination(ClientLevelWrapper_neoforge wrapper, Operation<Void> original) {
        // Only the idle timer is intercepted. Server-key replacement also invokes
        // tryUnloadFromWorld; that call and explicit world close remain native.
        if (wrapper.getDhLevel() == null || !DhNearbyLevelRetention.shouldRetain(wrapper.getWrappedMcObject())) {
            original.call(wrapper);
        }
    }
}
