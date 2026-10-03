package qouteall.imm_ptl.core.mixin.client.sound;

import com.mojang.blaze3d.audio.Channel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;
import qouteall.imm_ptl.core.teleportation.PortalSoundGain;
import qouteall.imm_ptl.core.teleportation.PortalSoundChannelState;

@Mixin(Channel.class)
public abstract class MixinChannel_PortalSound implements PortalSoundChannelState {
    @Shadow @Final private int source;
    @Unique private final PortalSoundGain portal$gain = new PortalSoundGain();

    @WrapOperation(method = "setVolume", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/openal/AL10;alSourcef(IIF)V", remap = false))
    private void portal$rememberAuthoredGain(int sourceId, int property, float gain, Operation<Void> original) {
        // This is after SoundEngine call-site compositions AND Channel HEAD
        // argument transformations, including SPA's managed-signal policy.
        original.call(sourceId, property, portal$gain.nativeWrite(gain));
    }

    @Override public void portal$setMuted(boolean muted) {
        if (portal$gain.muted() == muted) return;
        // Do not feed the saved effective value through upstream gain modifiers
        // a second time. Position/acoustics changes never author a new gain.
        AL10.alSourcef(source, AL10.AL_GAIN, portal$gain.setMuted(muted));
    }
    @Inject(method = {"stop", "destroy"}, at = @At("HEAD"))
    private void portal$forgetNativeSource(CallbackInfo ci) {
        PortalSoundManager.forgetChannel(source);
        portal$gain.reset();
    }
}
