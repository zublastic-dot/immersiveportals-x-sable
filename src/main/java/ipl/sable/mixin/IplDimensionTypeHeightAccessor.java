package ipl.sable.mixin;

import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Used only before levels exist; storage never follows the contextual Level height routes. */
@Mixin(DimensionType.class)
public interface IplDimensionTypeHeightAccessor {
    @Mutable @Accessor("minY") void iplsable$setStorageMinY(int value);
    @Mutable @Accessor("height") void iplsable$setStorageHeight(int value);
    @Mutable @Accessor("logicalHeight") void iplsable$setStorageLogicalHeight(int value);
}
