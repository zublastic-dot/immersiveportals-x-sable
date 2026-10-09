package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Preserves absolute section Y when Sable's index-keyed plot saves change storage profile. */
public final class IplPlotStorageMigration {
    public static final String PROFILE_TAG = "ipl_sable:storage_profile";
    private static final int PROFILE_VERSION = 1;
    // The untagged hosting dimension shipped by this fork before storage-profile metadata.
    private static final Profile LEGACY_HOSTING = new Profile(-64, 384);
    private static final int MIN_BLOCK_Y = -2032;
    private static final int MAX_BLOCK_Y_EXCLUSIVE = 2032;

    private IplPlotStorageMigration() {}

    /** Only the hosting ServerLevelPlot mixin calls this; ordinary Sable plots are untouched. */
    public static CompoundTag prepareForLoad(CompoundTag original, int minY, int height) {
        Profile target = profile(minY, height);
        Profile source = readProfile(original);
        validateSectionsAndPositions(original, source, target);

        // Validate the ENTIRE plot before handing any data to Sable, which loads incrementally.
        // Work on a copy so failures or repeated reads cannot rewrite the caller's saved tag.
        CompoundTag result = original.copy();
        if (!source.equals(target)) {
            CompoundTag chunks = result.getCompound("chunks");
            for (String chunkKey : chunks.getAllKeys()) {
                CompoundTag chunk = chunks.getCompound(chunkKey);
                CompoundTag oldSections = chunk.getCompound("sections");
                CompoundTag newSections = new CompoundTag();
                for (String indexKey : oldSections.getAllKeys()) {
                    int index = Integer.parseInt(indexKey);
                    int targetIndex = index + source.minSection() - target.minSection();
                    newSections.put(Integer.toString(targetIndex), oldSections.getCompound(indexKey));
                }
                chunk.put("sections", newSections);
                // Heightmap entries encode offsets from minY and use a height-dependent bit width.
                // Sable's existing loader primes every absent heightmap from the restored sections.
                chunk.remove("heightmaps");
            }
        }
        writeProfile(result, target);
        return result;
    }

    /** Stamp newly saved hosting plots without altering Sable's own data_version or payload. */
    public static CompoundTag stampSave(CompoundTag original, int minY, int height) {
        Profile storage = profile(minY, height);
        validateSectionsAndPositions(original, storage, storage);
        CompoundTag result = original.copy();
        writeProfile(result, storage);
        return result;
    }

    private static Profile readProfile(CompoundTag plot) {
        if (!plot.contains(PROFILE_TAG)) return LEGACY_HOSTING;
        if (!plot.contains(PROFILE_TAG, Tag.TAG_COMPOUND)) {
            throw invalid("storage profile is not a compound");
        }
        CompoundTag saved = plot.getCompound(PROFILE_TAG);
        requireInt(saved, "version", "storage profile");
        requireInt(saved, "min_y", "storage profile");
        requireInt(saved, "height", "storage profile");
        if (saved.getInt("version") != PROFILE_VERSION) {
            throw invalid("unsupported storage profile version " + saved.getInt("version"));
        }
        return profile(saved.getInt("min_y"), saved.getInt("height"));
    }

    private static Profile profile(int minY, int height) {
        // Minecraft 1.21.1 DimensionType codec and constructor constraints, including light padding.
        if (minY < MIN_BLOCK_Y || minY % 16 != 0 || height < 16 || height > 4064
            || height % 16 != 0 || (long) minY + height > MAX_BLOCK_Y_EXCLUSIVE) {
            throw invalid("unsupported profile minY=" + minY + ", height=" + height);
        }
        return new Profile(minY, height);
    }

    private static void validateSectionsAndPositions(CompoundTag plot, Profile source, Profile target) {
        requireCompound(plot, "chunks", "plot");
        CompoundTag chunks = plot.getCompound("chunks");
        for (String chunkKey : chunks.getAllKeys()) {
            requireCompound(chunks, chunkKey, "chunks");
            CompoundTag chunk = chunks.getCompound(chunkKey);
            requireCompound(chunk, "sections", "chunk " + chunkKey);
            CompoundTag sections = chunk.getCompound("sections");
            for (String indexKey : sections.getAllKeys()) {
                int index;
                try {
                    index = Integer.parseInt(indexKey);
                } catch (NumberFormatException e) {
                    throw invalid("non-integer section key " + indexKey + " in chunk " + chunkKey);
                }
                // Canonical keys prevent aliases such as "01" and "1" overwriting one another.
                if (!Integer.toString(index).equals(indexKey) || index < 0 || index >= source.sections()) {
                    throw invalid("section " + indexKey + " is outside its declared source profile in chunk " + chunkKey);
                }
                requireCompound(sections, indexKey, "sections in chunk " + chunkKey);
                int targetIndex = index + source.minSection() - target.minSection();
                if (targetIndex < 0 || targetIndex >= target.sections()) {
                    throw invalid("section Y=" + (index + source.minSection())
                        + " cannot fit destination storage in chunk " + chunkKey);
                }
            }
            validatePositions(chunk, "block_entities", source, target, chunkKey);
            validatePositions(chunk, "block_ticks", source, target, chunkKey);
            validatePositions(chunk, "fluid_ticks", source, target, chunkKey);
        }
    }

    private static void validatePositions(
        CompoundTag chunk, String key, Profile source, Profile target, String chunkKey
    ) {
        if (!chunk.contains(key)) return;
        if (!(chunk.get(key) instanceof ListTag entries)) {
            throw invalid(key + " is not a list in chunk " + chunkKey);
        }
        for (Tag entry : entries) {
            if (!(entry instanceof CompoundTag position)) {
                throw invalid(key + " contains a non-compound in chunk " + chunkKey);
            }
            requireInt(position, "y", key + " in chunk " + chunkKey);
            int y = position.getInt("y");
            if (!source.contains(y) || !target.contains(y)) {
                throw invalid(key + " Y=" + y + " is outside the source/destination storage in chunk " + chunkKey);
            }
        }
    }

    private static void requireCompound(CompoundTag tag, String key, String location) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) throw invalid(location + " has no compound " + key);
    }

    private static void requireInt(CompoundTag tag, String key, String location) {
        if (!tag.contains(key, Tag.TAG_INT)) throw invalid(location + " has no integer " + key);
    }

    private static void writeProfile(CompoundTag plot, Profile profile) {
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", PROFILE_VERSION);
        saved.putInt("min_y", profile.minY);
        saved.putInt("height", profile.height);
        plot.put(PROFILE_TAG, saved);
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Refusing to load/save hosted plot: " + reason + "; no sections were discarded");
    }

    private record Profile(int minY, int height) {
        int minSection() { return minY >> 4; }
        int sections() { return height >> 4; }
        boolean contains(int y) { return y >= minY && y < minY + height; }
    }
}
