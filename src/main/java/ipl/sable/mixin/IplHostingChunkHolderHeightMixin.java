package ipl.sable.mixin;

import ipl.sable.dim.IplChunkStorageHeight;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Block-change arrays and their packet section coordinates must use the same fixed origin. */
@Mixin(ChunkHolder.class)
public abstract class IplHostingChunkHolderHeightMixin {
    @ModifyVariable(
        method = "<init>(Lnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/server/level/ChunkHolder$LevelChangeListener;Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;)V",
        at = @At("HEAD"), argsOnly = true, index = 3, require = 1
    )
    private static LevelHeightAccessor ipl$pinChangeStorageHeight(LevelHeightAccessor original) {
        return IplChunkStorageHeight.forChunk(original, -1);
    }
}
