package ipl.sable.mixin;

import ipl.sable.dim.IplDeferredAirSections;
import ipl.sable.dim.IplDeferredAirSectionArchive;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

/** The archive travels with its chunk; unloaded chunks retain no separate global cache. */
@Mixin(ChunkAccess.class)
public abstract class IplDeferredAirSectionsMixin implements IplDeferredAirSections {
    @Unique private @Nullable IplDeferredAirSectionArchive iplsable$deferredAirArchive;

    @Override
    public @Nullable IplDeferredAirSectionArchive iplsable$getDeferredAirArchive() {
        return iplsable$deferredAirArchive;
    }

    @Override
    public void iplsable$setDeferredAirArchive(@Nullable IplDeferredAirSectionArchive archive) {
        iplsable$deferredAirArchive = archive;
    }
}
