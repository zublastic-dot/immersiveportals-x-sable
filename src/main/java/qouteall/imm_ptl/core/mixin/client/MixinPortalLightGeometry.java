package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalLighting;

/** State changes invalidate geometry; dynamic light writes do not. */
@Mixin(LevelChunk.class)
public abstract class MixinPortalLightGeometry {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void portalLightGeometryChanged(BlockPos pos, BlockState state, boolean moving,
            CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous != null && previous.isAir() != state.isAir()
                && ((LevelChunk) (Object) this).getLevel() instanceof ClientLevel level)
            PortalLighting.blockChanged(level, pos);
    }
}
