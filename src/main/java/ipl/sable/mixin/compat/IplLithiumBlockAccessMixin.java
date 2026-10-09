package ipl.sable.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Lithium's inlined block lookup in the height profile of the returned chunk. */
@Mixin(value = Level.class, priority = 900)
public abstract class IplLithiumBlockAccessMixin {
    // Lithium's default-priority overwrite has already been applied when this lower
    // priority mixin is prepared. Vanilla has no Level.getSectionIndex call here.
    @WrapOperation(
        method = "getBlockState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionIndex(I)I"),
        require = 0
    )
    private static int ipl$indexInChunkOwner(
        Level caller, int y, Operation<Integer> original, @Local LevelChunk chunk
    ) {
        // The plot bridge deliberately serves hosted chunks to parent-world callers.
        // Reuse the chunk Lithium already fetched: no second lookup or global override.
        // Even the same Level can expose contextual parent bounds while its chunk
        // retains immutable storage bounds. Level identity is not height identity.
        return chunk.getSectionIndex(y);
    }
}
