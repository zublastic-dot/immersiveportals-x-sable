package ipl.sable.mixin;

import ipl.sable.dim.IplChunkStorageHeight;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A hosting Level's routed world bounds must never change its chunks' section origin. */
@Mixin(ChunkAccess.class)
public abstract class IplHostingChunkHeightMixin {
    @ModifyVariable(
        method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/core/Registry;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V",
        at = @At("HEAD"), argsOnly = true, index = 3, require = 1
    )
    private static LevelHeightAccessor ipl$pinStorageHeight(
        LevelHeightAccessor original, ChunkPos pos, UpgradeData upgradeData,
        LevelHeightAccessor heightAccessor, Registry<Biome> biomes, long inhabitedTime,
        LevelChunkSection[] sections, BlendingData blendingData
    ) {
        return IplChunkStorageHeight.forChunk(original, sections == null ? -1 : sections.length);
    }
}
