package ipl.sable.mixin.diagnostics.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ipl.sable.diagnostics.IplIgnitionTrace;
import ipl.sable.diagnostics.IplIgnitionTraceFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Optional observations of existing operations; absent stages do not prove these hooks applied. */
@Mixin(Level.class)
public abstract class IplBlockReadTraceMixin {
    @WrapOperation(method = "getBlockState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
        require = 0)
    private LevelChunk ipl$observeSelectedChunk(Level level, int x, int z,
                                               Operation<LevelChunk> original,
                                               @Local(argsOnly = true) BlockPos pos) {
        LevelChunk chunk = original.call(level, x, z);
        if (IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.SERVER)) {
            IplIgnitionTraceFacts.observeBlockRead(level, pos, chunk, null, false);
        }
        return chunk;
    }

    @WrapOperation(method = "getBlockState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 0)
    private BlockState ipl$observeChunkResult(LevelChunk chunk, BlockPos pos,
                                             Operation<BlockState> original) {
        BlockState result = original.call(chunk, pos);
        if (IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.SERVER)) {
            IplIgnitionTraceFacts.observeBlockRead((Level) (Object) this, pos, chunk, result, true);
        }
        return result;
    }
}
