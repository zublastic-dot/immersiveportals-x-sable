package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.Nullable;

/** Resolves plot storage bounds without loading a chunk or changing the terrain frame. */
public final class IplHostedPositionHeight {
    private IplHostedPositionHeight() {}

    /** Null preserves the caller's normal bounds when no live hosted plot owns this position. */
    @Nullable
    public static Boolean isOutsideBuildHeight(Level context, BlockPos pos) {
        DimensionType storage = storageDimensionType(context, pos);
        if (storage == null) return null;
        int y = pos.getY();
        return y < storage.minY() || y >= storage.minY() + storage.height();
    }

    /** Scalar packet gates still need their original maximum for ordinary terrain positions. */
    public static int maximumForPosition(Level context, BlockPos pos, int fallback) {
        DimensionType storage = storageDimensionType(context, pos);
        return storage == null ? fallback : storage.minY() + storage.height();
    }

    /** The same live-plot ownership check is shared by block access and packet height gates. */
    @Nullable
    public static DimensionType storageDimensionType(Level context, BlockPos pos) {
        SubLevelContainer hosting = IplDimAgnostic.isHostingLevel(context)
            ? SubLevelContainer.getContainer(context) : IplDimAgnostic.getHostingContainerFor(context);
        if (hosting == null) return null;
        Level owner = hosting.getLevel();
        if (!IplDimAgnostic.isHostingLevel(owner)) return null;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!hosting.inBounds(chunkX, chunkZ)) return null;
        var plot = hosting.getPlot(chunkX, chunkZ);
        if (plot == null) return null;
        var subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.isRemoved() || subLevel.getLevel() != owner) return null;

        // Scalar Level bounds may currently describe parent terrain. Storage belongs to
        // the actual hosting world's immutable dimension type on both client and server.
        return owner.dimensionType();
    }
}
