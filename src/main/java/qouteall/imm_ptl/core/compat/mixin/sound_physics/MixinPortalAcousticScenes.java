package qouteall.imm_ptl.core.compat.mixin.sound_physics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sound_physics.PortalSoundPhysics;

@Pseudo
@Mixin(targets = "com.sonicether.soundphysics.acoustic.AcousticScenes", remap = false)
public abstract class MixinPortalAcousticScenes {
    @Inject(method = "createScene(Lnet/minecraft/client/Minecraft;Lcom/sonicether/soundphysics/acoustic/AcousticSceneContext;)Lcom/sonicether/soundphysics/acoustic/AcousticScene;",
        at = @At("HEAD"), cancellable = true)
    private static void portal$scene(CallbackInfoReturnable<Object> cir) {
        if (PortalSoundPhysics.evaluatingPortal()) cir.setReturnValue(PortalSoundPhysics.scene());
    }
}
