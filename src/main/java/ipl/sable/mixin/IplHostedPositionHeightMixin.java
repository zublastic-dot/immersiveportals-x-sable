package ipl.sable.mixin;

import ipl.sable.dim.IplHostedPositionHeight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;

/** Let plot-position access reach its chunk even when the caller has shorter terrain bounds. */
@Mixin(Level.class)
public abstract class IplHostedPositionHeightMixin implements LevelHeightAccessor {
    // Level inherits this overload from LevelHeightAccessor. Merge a concrete override
    // instead of injecting into an inherited method absent from Level's bytecode.
    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        Boolean hosted = IplHostedPositionHeight.isOutsideBuildHeight((Level) (Object) this, pos);
        return hosted != null ? hosted : this.isOutsideBuildHeight(pos.getY());
    }
}
