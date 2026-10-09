package ipl.sable.dim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/** Separates fixed chunk storage coordinates from the temporary parent terrain frame. */
public final class IplChunkStorageHeight {
    private IplChunkStorageHeight() {}

    /**
     * Called before ChunkAccess stores its accessor or allocates any section arrays.
     * A negative supplied count means the constructor has no supplied section array.
     */
    public static LevelHeightAccessor forChunk(LevelHeightAccessor original, int suppliedSectionCount) {
        if (!(original instanceof Level level) || !IplDimAgnostic.isHostingLevel(level)) return original;
        var dimension = level.dimensionType();
        return select(original, true, dimension.minY(), dimension.height(), suppliedSectionCount);
    }

    /**
     * Recover world identity for tracking and packet routing, which historically cast
     * the accessor to Level. Height calculations must keep using the fixed accessor.
     */
    public static LevelHeightAccessor unwrapOwner(LevelHeightAccessor accessor) {
        while (accessor instanceof FixedOwnerHeight storage) accessor = storage.owner();
        return accessor;
    }

    static LevelHeightAccessor select(
        LevelHeightAccessor original, boolean hosting, int minY, int height, int suppliedSectionCount
    ) {
        if (!hosting) return original;
        LevelHeightAccessor storage = original instanceof BlockAndTintGetter owner
            ? new FixedOwnerHeight(owner, minY, height) : LevelHeightAccessor.create(minY, height);
        // Vanilla merely warns and discards a mismatched array, replacing its blocks
        // with empty sections. Refuse that data loss; do not migrate or resize saves.
        if (suppliedSectionCount >= 0 && suppliedSectionCount != storage.getSectionsCount()) {
            throw new IllegalStateException("Hosted chunk section count " + suppliedSectionCount
                + " does not match storage dimension count " + storage.getSectionsCount()
                + " (minY=" + minY + ", height=" + height + "); refusing to discard supplied sections");
        }
        return storage;
    }

    /**
     * ScalableLux identifies a chunk's light backend through its height accessor. Keep
     * its BlockAndTintGetter/getLightEngine capability while every height-derived default
     * stays based on these fixed numbers, never the owner's contextual terrain bounds.
     */
    private record FixedOwnerHeight(BlockAndTintGetter owner, int minY, int height) implements BlockAndTintGetter {
        @Override public int getMinBuildHeight() { return minY; }
        @Override public int getHeight() { return height; }
        @Override public LevelLightEngine getLightEngine() { return owner.getLightEngine(); }
        @Override public float getShade(Direction direction, boolean shade) { return owner.getShade(direction, shade); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return owner.getBlockTint(pos, resolver); }
        @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return owner.getBlockEntity(pos); }
        @Override public BlockState getBlockState(BlockPos pos) { return owner.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return owner.getFluidState(pos); }
    }
}
