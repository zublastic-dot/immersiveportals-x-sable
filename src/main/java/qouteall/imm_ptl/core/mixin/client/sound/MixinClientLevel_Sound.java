package qouteall.imm_ptl.core.mixin.client.sound;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.teleportation.ClientSoundDispatch;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

/** Capture ownership at the producer; native sound instances and volume are retained. */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel_Sound {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private void playSound(double x, double y, double z, SoundEvent event,
                                  SoundSource category, float volume, float pitch, boolean delay, long seed) {
        throw new AssertionError();
    }

    @WrapMethod(method = "playSound")
    private void portal$ownedSound(double x, double y, double z, SoundEvent event,
                                   SoundSource category, float volume, float pitch, boolean delay, long seed,
                                   Operation<Void> original) {
        if (!IPGlobal.enableCrossPortalSound) {
            original.call(x, y, z, event, category, volume, pitch, delay, seed);
            return;
        }
        ClientLevel world = (ClientLevel) (Object) this;
        if (ClientSoundDispatch.defer(minecraft.isSameThread(), minecraft,
            () -> minecraft.level != null && (minecraft.level == world
                || ClientWorldLoader.getIsInitialized() && ClientWorldLoader.getClientWorlds().contains(world)),
            () -> playSound(x, y, z, event, category, volume, pitch, delay, seed))) return;
        try (var ignored = PortalSoundManager.producer(world)) {
            original.call(x, y, z, event, category, volume, pitch, delay, seed);
        }
    }

    @WrapOperation(method = {"playSeededSound", "playLocalSound"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"))
    private void portal$entitySoundOwner(SoundManager manager, SoundInstance sound, Operation<Void> original) {
        try (var ignored = PortalSoundManager.producer((ClientLevel) (Object) this)) {
            original.call(manager, sound);
        }
    }

    @WrapOperation(method = "playSound", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(DDD)D"))
    private double portal$physicalDelayDistance(Vec3 listener, double x, double y, double z, Operation<Double> original) {
        return PortalSoundManager.delayDistanceSquared((ClientLevel) (Object) this,
            new Vec3(x, y, z), listener, original.call(listener, x, y, z));
    }
}
