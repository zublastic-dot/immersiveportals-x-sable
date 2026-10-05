package ipl.sable.mixin;

import ipl.sable.dim.IplLightChunkOwnership;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSource.class)
public abstract class IplLightChunkGetterMixin {
    @Inject(method = "getChunkForLighting", at = @At("RETURN"), cancellable = true, require = 1)
    private void ipl$keepLightingInOwningWorld(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        // Generic chunk lookup deliberately exposes hosted plots to their parent for
        // rendering and interaction. A light engine must not use that foreign data.
        cir.setReturnValue(IplLightChunkOwnership.forWorld(
            ((LightChunkGetter) (Object) this).getLevel(), x, z, cir.getReturnValue()
        ));
    }
}
