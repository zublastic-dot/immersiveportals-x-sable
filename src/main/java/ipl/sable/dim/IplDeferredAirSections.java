package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

import javax.annotation.Nullable;

/** Chunk-owned, lossless storage for air sections outside the active hosting profile. */
public interface IplDeferredAirSections {
    @Nullable IplDeferredAirSectionArchive iplsable$getDeferredAirArchive();

    void iplsable$setDeferredAirArchive(@Nullable IplDeferredAirSectionArchive archive);

    default @Nullable ListTag iplsable$getDeferredAirSections() {
        var archive = iplsable$getDeferredAirArchive();
        return archive == null ? null : archive.decode();
    }

    /** Encoding consumes no ownership of the caller's mutable NBT. */
    default void iplsable$setDeferredAirSections(@Nullable ListTag sections) {
        iplsable$setDeferredAirArchive(IplDeferredAirSectionArchive.encode(sections));
    }

    static IplDeferredAirSections owner(ChunkAccess chunk) {
        return (IplDeferredAirSections) (chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk);
    }

    static void attach(ChunkAccess chunk, CompoundTag saved) {
        IplHostingChunkStorageMigration.validateDeferredAirSections(saved);
        owner(chunk).iplsable$setDeferredAirSections(saved.getList(
            IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND));
    }

    static void write(ChunkAccess chunk, CompoundTag saved) {
        var archive = owner(chunk).iplsable$getDeferredAirArchive();
        if (archive == null) saved.remove(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG);
        else saved.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, archive.decode());
    }

    static void copy(IplDeferredAirSections from, IplDeferredAirSections to) {
        to.iplsable$setDeferredAirArchive(from.iplsable$getDeferredAirArchive());
    }
}
