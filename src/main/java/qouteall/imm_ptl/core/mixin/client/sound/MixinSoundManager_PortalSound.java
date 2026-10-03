package qouteall.imm_ptl.core.mixin.client.sound;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

@Mixin(SoundManager.class)
public abstract class MixinSoundManager_PortalSound {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void portal$ownImmediate(SoundInstance sound, CallbackInfo ci) {
        if (!PortalSoundManager.bind(sound)) ci.cancel();
    }
    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void portal$ownDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (!PortalSoundManager.bind(sound)) ci.cancel();
    }
    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    private void portal$ownQueuedTickable(TickableSoundInstance sound, CallbackInfo ci) {
        if (!PortalSoundManager.bind(sound)) ci.cancel();
    }
}
