package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper_neoforge", remap = false)
public class MixinDhClientWrapper {
    @Inject(method = "getWrappedClientLevel(Z)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;", at = @At("HEAD"), cancellable = true)
    private void ip_tickLevel(boolean bypass, CallbackInfoReturnable<IClientLevelWrapper> cir) {
        var view = DhPortalRendering.TICK_VIEW.current();
        if (view != null && !bypass) cir.setReturnValue(view.level());
    }

    @Inject(method = "getPlayerBlockPos", at = @At("HEAD"), cancellable = true)
    private void ip_tickPosition(CallbackInfoReturnable<DhBlockPos> cir) {
        var view = DhPortalRendering.TICK_VIEW.current();
        if (view != null) cir.setReturnValue(new DhBlockPos((int)Math.floor(view.x()),
            (int)Math.floor(view.y()), (int)Math.floor(view.z())));
    }

    @Inject(method = "getPlayerChunkPos", at = @At("HEAD"), cancellable = true)
    private void ip_tickChunk(CallbackInfoReturnable<DhChunkPos> cir) {
        var view = DhPortalRendering.TICK_VIEW.current();
        if (view != null) cir.setReturnValue(new DhChunkPos((int)Math.floor(view.x()) >> 4,
            (int)Math.floor(view.z()) >> 4));
    }
}
