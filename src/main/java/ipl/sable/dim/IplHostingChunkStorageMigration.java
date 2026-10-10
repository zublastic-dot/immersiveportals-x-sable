package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ipl.sable.dim.IplAdaptiveStorageProfile.Bounds;

/** Height-relative metadata in vanilla Anvil chunks belonging to the hosting dimension. */
public final class IplHostingChunkStorageMigration {
    public static final String PROFILE_TAG = IplPlotStorageMigration.PROFILE_TAG;
    public static final String DEFERRED_AIR_SECTIONS_TAG = "ipl_sable:deferred_air_sections";
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private IplHostingChunkStorageMigration() {}

    public static CompoundTag prepareForLoad(CompoundTag original, int minY, int height) {
        Bounds target = new Bounds(minY, height);
        Source source = source(original);
        validateSectionInventory(original);
        if (source.profile.isPresent() && source.profile.get().equals(target)
            && !hasRestorableSection(original, target)) return original;

        // Inventory is shared with startup selection. Validate before copying or passing anything
        // to vanilla, whose loader otherwise silently drops out-of-range sections and upgrades.
        Optional<Bounds> occupied;
        try {
            occupied = IplSavedRegionHeightScanner.chunkBounds(original);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot safely inventory hosting chunk before height migration", failure);
        }
        occupied = union(occupied, indexedBounds(original));
        if (occupied.isPresent() && !target.contains(occupied.get())) {
            throw invalid("stored payload cannot fit the selected hosting profile");
        }
        SectionPlan sections = planSections(original, target);

        CompoundTag migrated = original.copy();
        migrated.put("sections", copyWithoutLight(sections.active));
        ListTag deferred = copyWithoutLight(sections.deferred);
        if (deferred.isEmpty()) migrated.remove(DEFERRED_AIR_SECTIONS_TAG);
        else migrated.put(DEFERRED_AIR_SECTIONS_TAG, deferred);
        int sectionOffset = source.minSection - (minY >> 4);
        if (original.contains("PostProcessing")) {
            ListTag old = (ListTag) original.get("PostProcessing");
            ListTag shifted = new ListTag();
            for (int i = 0; i < target.sectionCount(); i++) shifted.add(new ListTag());
            for (int i = 0; i < old.size(); i++) {
                int destination = i + sectionOffset;
                if (destination >= 0 && destination < shifted.size()) shifted.set(destination, old.get(i).copy());
            }
            migrated.put("PostProcessing", shifted);
        }
        if (original.get("UpgradeData") instanceof CompoundTag oldUpgrade && oldUpgrade.contains("Indices")) {
            CompoundTag shifted = new CompoundTag();
            CompoundTag indices = (CompoundTag) oldUpgrade.get("Indices");
            for (String key : indices.getAllKeys()) {
                int destination = Integer.parseInt(key) + sectionOffset;
                if (destination >= 0 && destination < target.sectionCount()) shifted.put(Integer.toString(destination), indices.get(key).copy());
            }
            migrated.getCompound("UpgradeData").put("Indices", shifted);
        }
        if (original.get("CarvingMasks") instanceof CompoundTag masks) {
            CompoundTag shifted = new CompoundTag();
            for (String key : masks.getAllKeys()) {
                long[] old = ((LongArrayTag) masks.get(key)).getAsLongArray();
                int wordOffset = sectionOffset * 64; // 16 block-Y values, 256 bits per Y.
                int length = Math.max(0, Math.min(target.height() * 4, old.length + wordOffset));
                long[] data = new long[length];
                int from = Math.max(0, -wordOffset);
                int to = Math.max(0, wordOffset);
                int count = Math.min(old.length - from, data.length - to);
                if (count > 0) System.arraycopy(old, from, data, to, count);
                shifted.putLongArray(key, data);
            }
            migrated.put("CarvingMasks", shifted);
        }

        // Heights encode offsets from minY, even if their packed-array length is unchanged.
        migrated.remove("Heightmaps");
        // Also prevents ScalableLux's RETURN hook from indexing old padding into new arrays.
        migrated.putBoolean("isLightOn", false);
        for (Tag entry : migrated.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) entry;
            section.remove("BlockLight");
            section.remove("SkyLight");
        }
        migrated.putInt("yPos", minY >> 4);
        migrated.putBoolean("shouldSave", true);
        writeProfile(migrated, target);
        return migrated;
    }

    /** The fresh tag returned by ChunkSerializer.write is exclusively owned here. */
    public static CompoundTag stampOwnedSave(CompoundTag owned, int minY, int height) {
        Bounds profile = new Bounds(minY, height);
        if (!owned.contains("yPos", Tag.TAG_INT) || owned.getInt("yPos") != minY >> 4) {
            throw invalid("saved chunk yPos does not match its immutable hosting storage");
        }
        writeProfile(owned, profile);
        return owned;
    }

    /** Validate archived bytes without forcing their air/biome-only sections into active storage. */
    public static void validateDeferredAirSections(CompoundTag root) {
        if (!root.contains(DEFERRED_AIR_SECTIONS_TAG)) return;
        if (!(root.get(DEFERRED_AIR_SECTIONS_TAG) instanceof ListTag sections)) {
            throw invalid("deferred air sections are not a list");
        }
        if (sections.size() > 256) throw invalid("too many deferred air sections");
        Set<Integer> seen = new HashSet<>();
        for (Tag entry : sections) {
            if (!(entry instanceof CompoundTag section)) throw invalid("deferred section is not a compound");
            if (!seen.add(sectionY(section))) throw invalid("duplicate deferred section Y");
            if (!isAirSection(section)) throw invalid("deferred section contains non-air blocks");
            validateBiomes(section);
        }
    }

    /** Shared startup/load validation, before either profile persistence or NBT transformation. */
    public static void validateSectionInventory(CompoundTag root) {
        validateDeferredAirSections(root);
        if (!(root.get("sections") instanceof ListTag sections) || sections.size() > 256) {
            throw invalid("invalid current section list");
        }
        Set<Integer> seen = new HashSet<>();
        for (Tag entry : sections) {
            if (!(entry instanceof CompoundTag section)) throw invalid("current section is not a compound");
            if (!seen.add(sectionY(section))) throw invalid("duplicate current section Y");
            isAirSection(section); // Also validates packed indices for sections that may be deferred.
            validateBiomes(section);
        }
    }

    /** Copy into the new save tag: the chunk's archive remains independently owned. */
    public static void writeDeferredAirSections(CompoundTag owned, ListTag deferred) {
        if (deferred == null || deferred.isEmpty()) owned.remove(DEFERRED_AIR_SECTIONS_TAG);
        else owned.put(DEFERRED_AIR_SECTIONS_TAG, deferred.copy());
        validateDeferredAirSections(owned);
    }

    private static boolean hasRestorableSection(CompoundTag original, Bounds target) {
        if (!original.contains(DEFERRED_AIR_SECTIONS_TAG)) return false;
        Source source = source(original);
        Set<Integer> current = new HashSet<>();
        for (Tag entry : original.getList("sections", Tag.TAG_COMPOUND)) current.add(sectionY((CompoundTag) entry));
        for (Tag entry : original.getList(DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND)) {
            int y = sectionY((CompoundTag) entry);
            if (inside(y, target) && !current.contains(y)
                && (source.profile.isEmpty() || !inside(y, source.profile.get()))) return true;
        }
        return false;
    }

    private static SectionPlan planSections(CompoundTag original, Bounds target) {
        Source source = source(original);
        List<CompoundTag> active = new ArrayList<>();
        Map<Integer, CompoundTag> deferred = new LinkedHashMap<>();
        Set<Integer> seenY = new HashSet<>();
        Map<Integer, CompoundTag> currentByY = new LinkedHashMap<>();
        Set<Integer> archivedY = new HashSet<>();
        for (Tag entry : original.getList(DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND)) archivedY.add(sectionY((CompoundTag) entry));
        if (!(original.get("sections") instanceof ListTag current)) throw invalid("sections are not a list");
        for (Tag entry : current) {
            if (!(entry instanceof CompoundTag section)) throw invalid("section is not a compound");
            int y = sectionY(section);
            if (!seenY.add(y)) throw invalid("duplicate current section Y");
            // ScalableLux serializes derived light padding just outside the old profile.
            // It must not hide an archived real section when the profile later expands.
            if (inside(y, target) && archivedY.contains(y) && source.profile.isPresent()
                && !inside(y, source.profile.get()) && section.getAllKeys().stream()
                    .allMatch(key -> key.equals("Y") || key.equals("BlockLight") || key.equals("SkyLight"))) continue;
            currentByY.put(y, section);
            if (inside(y, target)) active.add(section);
            else {
                if (!isAirSection(section)) throw invalid("non-air section cannot be deferred");
                validateBiomes(section);
                deferred.put(y, section);
            }
        }
        for (Tag entry : original.getList(DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) entry;
            int y = sectionY(section);
            CompoundTag currentSection = currentByY.get(y);
            boolean previouslyActive = source.profile.isPresent() && inside(y, source.profile.get());
            if (currentSection != null) {
                // Current values supersede the old archive after edits. Preserve only metadata
                // vanilla cannot round-trip, then overlay the latest section representation.
                deferred.put(y, archiveVersion(section, currentSection));
            } else if (previouslyActive) {
                // Absence inside the previous profile is authoritative, not a reason to resurrect
                // old blocks/biomes. Unknown metadata remains durable without stale active values.
                deferred.put(y, archiveVersion(section, null));
            } else {
                if (inside(y, target)) active.add(section);
                deferred.put(y, section);
            }
            // Vanilla reconstructs active section compounds on save, dropping unknown metadata.
            // Retain an archive after restoration, with current active values always authoritative.
        }
        return new SectionPlan(active, new ArrayList<>(deferred.values()));
    }

    private static CompoundTag archiveVersion(CompoundTag archived, CompoundTag current) {
        CompoundTag result = archived.copy();
        result.remove("block_states");
        result.remove("biomes");
        result.remove("BlockLight");
        result.remove("SkyLight");
        if (current != null) {
            boolean air = isAirSection(current);
            for (String key : current.getAllKeys()) {
                if (key.equals("BlockLight") || key.equals("SkyLight") || key.equals("block_states") && !air) continue;
                result.put(key, current.get(key).copy());
            }
        }
        return result;
    }

    private static ListTag copyWithoutLight(List<CompoundTag> sections) {
        ListTag result = new ListTag();
        for (CompoundTag section : sections) {
            CompoundTag copy = section.copy();
            copy.remove("BlockLight");
            copy.remove("SkyLight");
            result.add(copy);
        }
        return result;
    }

    private static boolean inside(int sectionY, Bounds target) {
        return sectionY >= target.minY() >> 4 && sectionY < target.maxY() >> 4;
    }

    private static int sectionY(CompoundTag section) {
        if (!(section.get("Y") instanceof NumericTag value)) throw invalid("section has no numeric Y");
        double y = value.getAsDouble();
        if (!Double.isFinite(y) || y != Math.rint(y) || y < -128 || y > 127) {
            throw invalid("section Y is not an exact byte-range integer");
        }
        return (int) y;
    }

    private static boolean isAirSection(CompoundTag section) {
        if (!section.contains("block_states")) return true;
        if (!(section.get("block_states") instanceof CompoundTag states)
            || !(states.get("palette") instanceof ListTag palette) || palette.isEmpty() || palette.size() > 4096) {
            throw invalid("malformed deferred block-state palette");
        }
        boolean air = true;
        for (Tag entry : palette) {
            if (!(entry instanceof CompoundTag state) || !state.contains("Name", Tag.TAG_STRING)) {
                throw invalid("malformed deferred block state");
            }
            air &= AIR.contains(state.getString("Name"));
        }
        if (states.contains("data") && !(states.get("data") instanceof LongArrayTag)) {
            throw invalid("malformed deferred block-state data");
        }
        long[] packed = states.getLongArray("data");
        if (palette.size() > 1) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            int perWord = 64 / bits;
            if (packed.length != (4096 + perWord - 1) / perWord) throw invalid("truncated deferred block-state data");
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                if (((packed[i / perWord] >>> ((i % perWord) * bits)) & mask) >= palette.size()) {
                    throw invalid("deferred block-state index exceeds palette");
                }
            }
        }
        return air;
    }

    private static void validateBiomes(CompoundTag section) {
        if (!section.contains("biomes")) return;
        if (!(section.get("biomes") instanceof CompoundTag biomes)
            || !(biomes.get("palette") instanceof ListTag palette) || palette.isEmpty() || palette.size() > 64
            || palette.getElementType() != Tag.TAG_STRING
            || biomes.contains("data") && !(biomes.get("data") instanceof LongArrayTag)) {
            throw invalid("malformed deferred biome palette");
        }
        if (palette.size() > 1) {
            int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
            int perWord = 64 / bits;
            long[] packed = biomes.getLongArray("data");
            if (packed.length != (64 + perWord - 1) / perWord) throw invalid("truncated deferred biome data");
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 64; i++) {
                if (((packed[i / perWord] >>> ((i % perWord) * bits)) & mask) >= palette.size()) {
                    throw invalid("deferred biome index exceeds palette");
                }
            }
        }
    }

    private record SectionPlan(List<CompoundTag> active, List<CompoundTag> deferred) {}

    /** Includes positions represented by indexed metadata, even when all block palettes are air. */
    public static Optional<Bounds> indexedBounds(CompoundTag chunk) {
        Source source = source(chunk);
        Optional<Bounds> result = Optional.empty();
        if (chunk.contains("PostProcessing")) {
            if (!(chunk.get("PostProcessing") instanceof ListTag sections)) throw invalid("PostProcessing is not a list");
            if (sections.size() > 254) throw invalid("PostProcessing has too many sections");
            for (int i = 0; i < sections.size(); i++) {
                if (!(sections.get(i) instanceof ListTag positions)) throw invalid("PostProcessing section is not a list");
                for (Tag value : positions) {
                    if (!(value instanceof ShortTag packed) || packed.getAsShort() < 0 || packed.getAsShort() > 4095) {
                        throw invalid("invalid packed PostProcessing position");
                    }
                }
                if (!positions.isEmpty()) result = union(result, Optional.of(section(source, i)));
            }
        }
        if (chunk.contains("UpgradeData")) {
            if (!(chunk.get("UpgradeData") instanceof CompoundTag upgrade)) throw invalid("UpgradeData is not a compound");
            if (upgrade.contains("Indices")) {
                if (!(upgrade.get("Indices") instanceof CompoundTag indices)) throw invalid("UpgradeData.Indices is not a compound");
                for (String key : indices.getAllKeys()) {
                    int index = index(key);
                    if (!(indices.get(key) instanceof IntArrayTag values)) throw invalid("UpgradeData index is not an integer array");
                    for (int value : values.getAsIntArray()) if (value < 0 || value > 4095) throw invalid("invalid packed upgrade position");
                    if (values.size() > 0) result = union(result, Optional.of(section(source, index)));
                }
            }
            for (String key : new String[] {"neighbor_block_ticks", "neighbor_fluid_ticks"}) {
                if (!upgrade.contains(key)) continue;
                if (!(upgrade.get(key) instanceof ListTag ticks)) throw invalid("upgrade neighbor ticks are not a list");
                for (Tag entry : ticks) {
                    if (!(entry instanceof CompoundTag tick) || !tick.contains("y", Tag.TAG_INT)) {
                        throw invalid("upgrade neighbor tick has no integer Y");
                    }
                    int y = tick.getInt("y");
                    int index = (y >> 4) - source.minSection;
                    if (index < 0) throw invalid("upgrade neighbor tick is below its source profile");
                    result = union(result, Optional.of(section(source, index)));
                }
            }
        }
        if (chunk.contains("CarvingMasks")) {
            if (!(chunk.get("CarvingMasks") instanceof CompoundTag masks)) throw invalid("CarvingMasks is not a compound");
            for (String key : masks.getAllKeys()) {
                if (!(masks.get(key) instanceof LongArrayTag mask)) throw invalid("carving mask is not a long array");
                long[] words = mask.getAsLongArray();
                if (words.length > 4064 * 4) throw invalid("carving mask exceeds native height range");
                for (int word = 0; word < words.length; word++) {
                    if (words[word] != 0) result = union(result, Optional.of(section(source, word >> 6)));
                }
            }
        }
        return result;
    }

    private static Source source(CompoundTag chunk) {
        if (!chunk.contains("yPos", Tag.TAG_INT)) throw invalid("chunk has no saved minimum section yPos");
        int minSection = chunk.getInt("yPos");
        if (minSection < -127 || minSection > 126) throw invalid("chunk yPos exceeds native height range");
        if (!chunk.contains(PROFILE_TAG)) return new Source(minSection, Optional.empty());
        if (!(chunk.get(PROFILE_TAG) instanceof CompoundTag tag)
            || !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1
            || !tag.contains("min_y", Tag.TAG_INT) || !tag.contains("height", Tag.TAG_INT)) {
            throw invalid("invalid or unsupported stored chunk profile");
        }
        Bounds profile = new Bounds(tag.getInt("min_y"), tag.getInt("height"));
        if ((profile.minY() >> 4) != minSection) throw invalid("stored chunk profile disagrees with yPos");
        return new Source(minSection, Optional.of(profile));
    }

    private static Bounds section(Source source, int index) {
        int absolute = source.minSection + index;
        if (absolute < -127 || absolute > 126 || source.profile.isPresent() && index >= source.profile.get().sectionCount()) {
            throw invalid("indexed payload exceeds its declared source profile");
        }
        return new Bounds(absolute << 4, 16);
    }

    private static int index(String key) {
        try {
            int value = Integer.parseInt(key);
            if (value < 0 || value >= 254 || !Integer.toString(value).equals(key)) throw invalid("invalid section index " + key);
            return value;
        } catch (NumberFormatException failure) {
            throw invalid("invalid section index " + key);
        }
    }

    private static Optional<Bounds> union(Optional<Bounds> a, Optional<Bounds> b) {
        return a.isEmpty() ? b : b.map(a.get()::union).or(() -> a);
    }

    private static void writeProfile(CompoundTag tag, Bounds profile) {
        CompoundTag metadata = new CompoundTag();
        metadata.putInt("version", 1);
        metadata.putInt("min_y", profile.minY());
        metadata.putInt("height", profile.height());
        tag.put(PROFILE_TAG, metadata);
    }

    private record Source(int minSection, Optional<Bounds> profile) {}

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Refusing hosting Anvil height migration: " + reason + "; no payload was discarded");
    }
}
