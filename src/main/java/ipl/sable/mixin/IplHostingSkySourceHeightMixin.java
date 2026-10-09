package ipl.sable.mixin;

import ipl.sable.dim.IplChunkStorageHeight;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Covers SkyLightEngine's empty-chunk source, which is built directly from its Level. */
@Mixin(ChunkSkyLightSources.class)
public abstract class IplHostingSkySourceHeightMixin {
    @ModifyVariable(method = "<init>(Lnet/minecraft/world/level/LevelHeightAccessor;)V",
        at = @At("HEAD"), argsOnly = true, index = 1, require = 1)
    private static LevelHeightAccessor ipl$pinSkySourceHeight(LevelHeightAccessor original) {
        return IplChunkStorageHeight.forChunk(original, -1);
    }
}
