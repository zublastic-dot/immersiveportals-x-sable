package qouteall.imm_ptl.core.compat.mixin.iris;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalShaderLighting;

/** Source shadows must also notice solid-to-transparent replacements at a stopped sun clock. */
@Mixin(LevelChunk.class)
public abstract class MixinIrisPortalSunGeometry {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void portalSunGeometryChanged(BlockPos pos, BlockState state, boolean moving,
            CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        // Air transitions already reach PortalShaderLighting through MixinPortalLightGeometry.
        if (previous != null && previous != state && previous.isAir() == state.isAir()
                && ((LevelChunk) (Object) this).getLevel() instanceof ClientLevel level)
            PortalShaderLighting.blockChanged(level, pos);
    }
}
