package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplChunkStorageHeight;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keep Sable's preallocated section array consistent with the chunk storage accessor. */
@Pseudo
@Mixin(value = LevelPlot.class, remap = false)
public abstract class IplHostingPlotHeightMixin {
    @WrapOperation(
        method = "newEmptyChunk(Lnet/minecraft/world/level/ChunkPos;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I"),
        require = 1
    )
    private int ipl$storageSections(Level level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getSectionsCount()
            : original.call(level);
    }

    @WrapOperation(method = "getCenterBlock()Lnet/minecraft/core/BlockPos;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"), require = 1)
    private int ipl$storageCenterMin(Level level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getMinBuildHeight() : original.call(level);
    }

    @WrapOperation(method = "getCenterBlock()Lnet/minecraft/core/BlockPos;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"), require = 1)
    private int ipl$storageCenterMax(Level level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getMaxBuildHeight() : original.call(level);
    }
}
