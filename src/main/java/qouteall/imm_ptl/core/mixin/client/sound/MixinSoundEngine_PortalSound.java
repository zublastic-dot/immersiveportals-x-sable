package qouteall.imm_ptl.core.mixin.client.sound;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Listener;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

import java.util.*;
import java.util.function.Consumer;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine_PortalSound {
    @Shadow @Final private Listener listener;
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
    @Shadow @Final private Map<SoundInstance, Integer> queuedSounds;
    @Shadow @Final private List<TickableSoundInstance> queuedTickableSounds;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void portal$admitDirectEngineCall(SoundInstance sound, CallbackInfo ci) {
        if (!PortalSoundManager.bind(sound)) ci.cancel();
    }

    @WrapOperation(method = "play", at = @At(value = "INVOKE", target =
        "Lnet/neoforged/neoforge/client/ClientHooks;playSound(Lnet/minecraft/client/sounds/SoundEngine;Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/resources/sounds/SoundInstance;"))
    private SoundInstance portal$preserveEventProvenance(SoundEngine engine, SoundInstance sound,
                                                        Operation<SoundInstance> original) {
        SoundInstance replacement = original.call(engine, sound);
        return PortalSoundManager.inherit(sound, replacement) ? replacement : null;
    }

    @WrapOperation(method = "play", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 portal$startPosition(double x, double y, double z, Operation<Vec3> original,
                                      @Local(argsOnly = true) SoundInstance sound) {
        return PortalSoundManager.presentation(sound, original.call(x, y, z), listener.getTransform().position());
    }
    @WrapOperation(method = "tickNonPaused", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 portal$tickingPosition(double x, double y, double z, Operation<Vec3> original,
                                        @Local TickableSoundInstance sound) {
        return PortalSoundManager.presentation(sound, original.call(x, y, z), listener.getTransform().position());
    }
    @WrapOperation(method = "play", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V"))
    private void portal$associateChannel(ChannelAccess.ChannelHandle handle, Consumer<Channel> setup,
                                         Operation<Void> original, @Local(argsOnly = true) SoundInstance sound) {
        original.call(handle, (Consumer<Channel>) channel -> {
            PortalSoundManager.associateChannel(sound, channel);
            setup.accept(channel);
        });
    }
    @Inject(method = "play", at = @At("RETURN"))
    private void portal$presentStartedSound(SoundInstance sound, CallbackInfo ci) {
        PortalSoundManager.afterPlay(sound, instanceToChannel, listener.getTransform().position());
    }
    @Inject(method = "tickNonPaused", at = @At("TAIL"))
    private void portal$updateStaticAndMovingSources(CallbackInfo ci) {
        Set<SoundInstance> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        queued.addAll(queuedSounds.keySet());
        queued.addAll(queuedTickableSounds);
        PortalSoundManager.tick(instanceToChannel, queued, listener.getTransform().position());
    }
    @Inject(method = "stopAll", at = @At("RETURN"))
    private void portal$clearOwnership(CallbackInfo ci) { PortalSoundManager.clear(); }
}
