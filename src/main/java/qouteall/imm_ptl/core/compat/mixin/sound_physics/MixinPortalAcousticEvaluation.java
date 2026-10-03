package qouteall.imm_ptl.core.compat.mixin.sound_physics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sound_physics.PortalSoundPhysics;

@Pseudo
@Mixin(targets = "com.sonicether.soundphysics.SoundPhysics", remap = false)
public abstract class MixinPortalAcousticEvaluation {
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void portal$linked(CallbackInfo ci) { PortalSoundPhysics.linked(); }

    @WrapMethod(method = "evaluateEnvironment")
    private static Vec3 portal$evaluate(int source, double x, double y, double z,
                                       SoundSource category, ResourceLocation sound, boolean aux,
                                       Operation<Vec3> original) {
        var route = PortalSoundPhysics.begin(source);
        if (route == null) return original.call(source, x, y, z, category, sound, aux);
        try {
            Vec3 virtual = route.virtualSourcePosition();
            return original.call(source, virtual.x, virtual.y, virtual.z, category, sound, aux);
        } finally { PortalSoundPhysics.end(); }
    }
}
