package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.uniforms.CommonUniforms;
import net.minecraft.core.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(value = CommonUniforms.class, remap = false)
public class MixinIrisPortalEyeBrightness {
    @ModifyArg(
        method = "getEyeBrightness",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;"),
        index = 0
    )
    private static Position ip_destinationEyePosition(Position entityEyes) {
        // IP switches client.level without moving the camera entity. Sampling
        // that entity's coordinates in the destination can read underground
        // skylight, suppressing shader fog even when the portal looks outdoors.
        // This position already includes the transformed eye/camera offset.
        return WorldRenderInfo.isRendering() ? WorldRenderInfo.getCameraPos() : entityEyes;
    }
}
