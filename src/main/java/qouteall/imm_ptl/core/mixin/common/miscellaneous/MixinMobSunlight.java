package qouteall.imm_ptl.core.mixin.common.miscellaneous;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.sunlight.SunlightServer;

@Mixin(Mob.class)
public abstract class MixinMobSunlight {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void ip_sharedSolarExposure(CallbackInfoReturnable<Boolean> cir) {
        Mob mob = (Mob) (Object) this;
        if (mob.level() instanceof ServerLevel world && SunlightServer.enabled(world))
            cir.setReturnValue(SunlightServer.burnTick(mob, world));
    }
}
