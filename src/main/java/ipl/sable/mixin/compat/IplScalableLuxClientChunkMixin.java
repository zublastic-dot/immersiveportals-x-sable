package ipl.sable.mixin.compat;

import ipl.sable.dim.IplLightChunkOwnership;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "ca.spottedleaf.starlight.common.light.vanillainterface.BaseLevelLightEngineVanillaInterface", remap = false)
public abstract class IplScalableLuxClientChunkMixin {
    @Unique private BlockGetter ipl$lightOwner;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void ipl$captureLightOwner(LightChunkGetter chunks, boolean block, boolean sky, CallbackInfo ci) {
        ipl$lightOwner = chunks.getLevel();
    }

    @Inject(method = "scalablelux$clientChunkLoad", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$ignoreForeignLightArrays(ChunkPos pos, LevelChunk chunk, CallbackInfo ci) {
        // Explicit chunks bypass both lookup guards. Never write parent-indexed
        // nibble arrays into a hosted chunk, even when section counts happen to match.
        if (!IplLightChunkOwnership.belongsTo(ipl$lightOwner, chunk)) {
            ci.cancel();
        }
    }
}
