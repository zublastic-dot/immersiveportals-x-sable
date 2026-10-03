package qouteall.imm_ptl.core.mixin.client.sound;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

@Mixin(Channel.class)
public abstract class MixinChannel_PortalSound {
    @Shadow @Final private int source;
    @Inject(method = {"stop", "destroy"}, at = @At("HEAD"))
    private void portal$forgetNativeSource(CallbackInfo ci) { PortalSoundManager.forgetChannel(source); }
}
