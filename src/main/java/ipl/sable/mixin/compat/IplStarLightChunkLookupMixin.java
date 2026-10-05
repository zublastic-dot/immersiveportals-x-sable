package ipl.sable.mixin.compat;

import ipl.sable.dim.IplLightChunkOwnership;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "ca.spottedleaf.starlight.common.light.StarLightInterface", remap = false)
public abstract class IplStarLightChunkLookupMixin {
    @Shadow @Final protected Level world;

    @Inject(method = "getAnyChunkNow", at = @At("RETURN"), cancellable = true, require = 1)
    private void ipl$keepImmediateLightingInOwningWorld(
        int x, int z, CallbackInfoReturnable<ChunkAccess> cir
    ) {
        // ScalableLux's immediate FULL lookup bypasses ChunkSource.getChunkForLighting.
        cir.setReturnValue(IplLightChunkOwnership.forWorld(world, cir.getReturnValue()));
    }
}
