package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IplSavedRegionHeightScannerTest {
    @TempDir Path folder;

    @Test void emptyAndZeroByteRegionsDoNotKeepTheOld254SectionFloor() throws Exception {
        Files.createDirectories(folder.resolve("poi"));
        Files.write(folder.resolve("poi/r.0.0.mca"), new byte[0]);
        region("region", terrain(-127, "minecraft:air"), 2, false, true);
        assertTrue(IplSavedRegionHeightScanner.scan(folder).isEmpty());
    }

    @Test void gzipDeflateRawAndLz4RetainOnlyOccupiedPaletteSection() throws Exception {
        for (int codec = 1; codec <= 4; codec++) {
            Path file = region("region", terrain(12, "minecraft:obsidian"), codec, false, true);
            byte[] before = Files.readAllBytes(file);
            assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)), IplSavedRegionHeightScanner.scan(folder));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void realReadOnlyOuterScanUnionsSableAndOrdinaryStorage() throws Exception {
        region("region", terrain(12, "minecraft:obsidian"), 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)), IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void scalableLuxIntegerLightPaddingDoesNotBecomeOccupiedStorage() throws Exception {
        CompoundTag root = terrain(12, "minecraft:obsidian");
        ListTag sections = root.getList("sections", 10);
        sections.getCompound(0).putInt("Y", 12);
        for (int y : new int[] {-128, 127}) {
            CompoundTag padding = new CompoundTag();
            padding.putInt("Y", y);
            padding.putByteArray("BlockLight", new byte[2048]);
            sections.add(padding);
        }
        region("region", root, 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)), IplSavedRegionHeightScanner.scan(folder));
        sections.getCompound(0).putInt("Y", 128);
        region("region", root, 2, false, true);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void customBiomesInAirUseLosslessArchiveInsteadOfForcingSectionAllocation() throws Exception {
        CompoundTag root = terrain(-100, "minecraft:air");
        CompoundTag biomes = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:the_void"));
        biomes.put("palette", palette);
        root.getList("sections", 10).getCompound(0).put("biomes", biomes);
        region("region", root, 2, false, true);
        assertTrue(IplSavedRegionHeightScanner.scan(folder).isEmpty());
        palette.set(0, StringTag.valueOf("minecraft:desert"));
        region("region", root, 2, false, true);
        var report = IplSavedRegionHeightScanner.scanReport(folder);
        assertTrue(report.bounds().isEmpty());
        assertEquals(1L, report.counts().get("retained_air_biome_sections"));
        root.getList("sections", 10).getCompound(0).getCompound("block_states").getList("palette", 10)
            .getCompound(0).putString("Name", "minecraft:stone");
        region("region", root, 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-1600, 16)), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void externalAndUnpaddedRecordIsSupportedButMissingPayloadFails() throws Exception {
        Path file = region("region", terrain(-6, "minecraft:stone"), 2, true, false);
        Path external = folder.resolve("region/c.0.0.mcc");
        byte[] before = Files.readAllBytes(external);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-96, 16)), IplSavedRegionHeightScanner.scan(folder));
        assertArrayEquals(before, Files.readAllBytes(external));
        Files.delete(external);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
        Files.delete(file);
        Files.write(external, before);
        assertEquals(Optional.of(IplAdaptiveStorageProfile.FULL_RANGE), IplSavedRegionHeightScanner.scan(folder),
            "An orphan .mcc has no authoritative compression header, so retains the conservative floor");
    }

    @Test void actualTruncationAndOverlappingSectorsAreRejected() throws Exception {
        Path file = region("region", terrain(12, "minecraft:obsidian"), 2, false, false);
        byte[] valid = Files.readAllBytes(file);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)), IplSavedRegionHeightScanner.scan(folder));
        Files.write(file, Arrays.copyOf(valid, valid.length - 1));
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
        ByteBuffer.wrap(valid).putInt(4, ByteBuffer.wrap(valid).getInt(0));
        Files.write(file, valid);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void entitiesAndNestedPassengersUseRawPositions() throws Exception {
        CompoundTag root = versioned();
        CompoundTag parent = entity(208.25);
        ListTag passengers = new ListTag();
        passengers.add(entity(-96.5));
        parent.put("Passengers", passengers);
        ListTag entities = new ListTag();
        entities.add(parent);
        root.put("Entities", entities);
        Path file = region("entities", root, 1, false, true);
        byte[] before = Files.readAllBytes(file);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-112, 336)), IplSavedRegionHeightScanner.scan(folder));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void invalidEntityPositionFailsAndOutOfBuildHeightEntityKeepsFullRange() throws Exception {
        CompoundTag root = versioned();
        ListTag entities = new ListTag();
        entities.add(entity(Double.NaN));
        root.put("Entities", entities);
        region("entities", root, 2, false, true);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
        entities.set(0, entity(3000));
        region("entities", root, 2, false, true);
        assertEquals(Optional.of(IplAdaptiveStorageProfile.FULL_RANGE), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void positionedMetadataAndPoiRecordsAreIncludedWithoutBlocks() throws Exception {
        CompoundTag terrain = terrain(0, "minecraft:air");
        ListTag ticks = new ListTag();
        CompoundTag tick = new CompoundTag();
        tick.putInt("y", -100);
        ticks.add(tick);
        terrain.put("fluid_ticks", ticks);
        ListTag lights = new ListTag();
        CompoundTag light = new CompoundTag();
        light.putLong("pos", 600L);
        light.putByte("level", (byte) 12);
        lights.add(light);
        terrain.put("neoforge:aux_lights", lights);
        region("region", terrain, 2, false, true);
        CompoundTag poi = versioned();
        CompoundTag sections = new CompoundTag();
        CompoundTag section = new CompoundTag();
        ListTag records = new ListTag();
        CompoundTag record = new CompoundTag();
        record.putIntArray("pos", new int[] {0, 1000, 0});
        records.add(record);
        section.put("Records", records);
        sections.put("62", section);
        poi.put("Sections", sections);
        region("poi", poi, 3, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-112, 1120)), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void oldFormatsKeepConservativeFloorButModernProtoUsesOccupiedBounds() throws Exception {
        CompoundTag old = terrain(12, "minecraft:stone");
        old.putInt("DataVersion", 100);
        region("region", old, 2, false, true);
        assertEquals(Optional.of(IplAdaptiveStorageProfile.FULL_RANGE), IplSavedRegionHeightScanner.scan(folder));
        CompoundTag proto = terrain(12, "minecraft:stone");
        proto.putString("Status", "minecraft:carvers");
        region("region", proto, 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void protoPendingEntitiesAndCarvingMasksProtectTheirAbsolutePositions() throws Exception {
        CompoundTag proto = terrain(12, "minecraft:air");
        proto.putString("Status", "minecraft:carvers");
        ListTag pending = new ListTag();
        pending.add(entity(600));
        proto.put("entities", pending);
        CompoundTag masks = new CompoundTag();
        long[] words = new long[121 * 64];
        words[120 * 64] = 1;
        masks.putLongArray("AIR", words);
        proto.put("CarvingMasks", masks);
        region("region", proto, 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-112, 720)), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void everyModernStatusCanRetainEmptyBiomeSectionsWithoutKeepingTheOldEnvelope() throws Exception {
        for (String status : new String[] {"empty", "structure_starts", "structure_references", "biomes", "noise",
            "surface", "carvers", "features", "initialize_light", "light", "spawn", "full"}) {
            CompoundTag root = terrain(-127, "minecraft:air");
            root.putString("Status", "minecraft:" + status);
            CompoundTag biomes = new CompoundTag();
            ListTag palette = new ListTag();
            palette.add(StringTag.valueOf("minecraft:plains"));
            biomes.put("palette", palette);
            root.getList("sections", 10).getCompound(0).put("biomes", biomes);
            assertTrue(IplSavedRegionHeightScanner.chunkBounds(root).isEmpty(), status);
        }
    }

    @Test void deferredAirSectionsAreValidatedWithoutForcingAnAllocationFloor() throws Exception {
        CompoundTag root = terrain(0, "minecraft:air");
        ListTag deferred = new ListTag();
        CompoundTag archived = terrain(-100, "minecraft:air").getList("sections", 10).getCompound(0).copy();
        archived.putString("custom_metadata", "retained verbatim");
        deferred.add(archived);
        root.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, deferred);
        CompoundTag before = root.copy();
        region("region", root, 2, false, true);
        assertTrue(IplSavedRegionHeightScanner.scan(folder).isEmpty());
        assertEquals(before, root, "Inventory is nonmutating");
        archived.getCompound("block_states").getList("palette", 10).getCompound(0).putString("Name", "minecraft:stone");
        region("region", root, 2, false, true);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void malformedDeferredListAndDuplicateAbsoluteSectionsAbortRatherThanDisappear() throws Exception {
        CompoundTag root = terrain(0, "minecraft:air");
        root.putString(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, "invalid");
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.chunkBounds(root));
        ListTag deferred = new ListTag();
        CompoundTag archived = terrain(-100, "minecraft:air").getList("sections", 10).getCompound(0).copy();
        deferred.add(archived);
        deferred.add(archived.copy());
        root.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, deferred);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.chunkBounds(root));
    }

    @Test void currentSectionCorruptionFailsDuringStartupInventoryBeforeProfileSelection() throws Exception {
        CompoundTag duplicate = terrain(-100, "minecraft:air");
        ListTag sections = duplicate.getList("sections", 10);
        sections.add(sections.getCompound(0).copy());
        region("region", duplicate, 2, false, true);
        IOException invalid = assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
        assertInstanceOf(IllegalStateException.class, invalid.getCause(), "Shared validator failure remains available for diagnosis");

        CompoundTag malformed = terrain(-100, "minecraft:air");
        ListTag palette = malformed.getList("sections", 10).getCompound(0).getCompound("block_states").getList("palette", 10);
        CompoundTag caveAir = new CompoundTag();
        caveAir.putString("Name", "minecraft:cave_air");
        palette.add(caveAir); // Multiple entries need a complete packed 4096-cell data array.
        region("region", malformed, 2, false, true);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void optInArchiveAuditMeasuresEncodedBytesAndDistinguishesProjectedFromPersistedProof() throws Exception {
        CompoundTag root = terrain(0, "minecraft:stone");
        CompoundTag outside = terrain(-100, "minecraft:air").getList("sections", 10).getCompound(0).copy();
        outside.putString("example:metadata", "exactly retained");
        root.getList("sections", 10).add(outside);
        Path file = region("region", root, 2, false, true);
        byte[] before = Files.readAllBytes(file);
        var report = IplSavedRegionHeightScanner.auditDeferredArchives(folder, -96, 608);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, report.terrainRecords());
        assertEquals(1, report.archiveChunks());
        assertEquals(1, report.archivedSections());
        assertTrue(report.persistedArchiveSha256ByChunk().isEmpty(), "Projected retention is not proof of a disk archive");
        CompoundTag migrated = IplHostingChunkStorageMigration.prepareForLoad(root, -96, 608);
        ListTag archived = migrated.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, 10);
        // Equivalent NBT compounds can serialize keys in a different map order, so a fresh
        // gzip encoding is not a stable byte-count oracle. Content hashes are canonical.
        assertTrue(report.totalCompressedBytes() > 0);
        assertEquals(report.totalCompressedBytes(), report.maxCompressedBytes(), "One archive contributes the total and maximum");
        assertEquals((16L + report.totalCompressedBytes() + 7) & ~7L, report.estimatedEncodedArrayShallowBytes());
        assertEquals(IplSavedRegionHeightScanner.archiveDigest(archived), report.projectedArchiveSha256ByChunk().get("r.0.0.mca#0"));

        region("region", migrated, 2, false, true);
        var persisted = IplSavedRegionHeightScanner.auditDeferredArchives(folder, -96, 608);
        assertEquals(report.projectedArchiveSha256ByChunk(), persisted.persistedArchiveSha256ByChunk());
        assertEquals(report.projectedArchiveSha256ByChunk(), persisted.projectedArchiveSha256ByChunk());
        assertTrue(persisted.totalCompressedBytes() > 0);
        assertEquals(persisted.totalCompressedBytes(), persisted.maxCompressedBytes());
    }

    @Test void archiveDigestIgnoresCompoundAndSectionOrderingButPreservesNbtTypes() throws Exception {
        CompoundTag first = terrain(-100, "minecraft:air").getList("sections", 10).getCompound(0).copy();
        first.putInt("Aa", 1);
        first.putInt("BB", 2); // These keys collide under String.hashCode; insertion order is not canonical.
        CompoundTag reordered = first.copy();
        reordered.remove("Aa");
        reordered.remove("BB");
        reordered.putInt("BB", 2);
        reordered.putInt("Aa", 1);
        CompoundTag second = terrain(100, "minecraft:air").getList("sections", 10).getCompound(0).copy();
        ListTag a = new ListTag();
        a.add(first);
        a.add(second);
        ListTag b = new ListTag();
        b.add(second.copy());
        b.add(reordered);
        assertEquals(IplSavedRegionHeightScanner.archiveDigest(a), IplSavedRegionHeightScanner.archiveDigest(b));
        reordered.putLong("Aa", 1);
        assertNotEquals(IplSavedRegionHeightScanner.archiveDigest(a), IplSavedRegionHeightScanner.archiveDigest(b));
    }

    @Test void unhandledProtoGenerationStateAndUnknownStatusRetainFullFloorWithReasons() throws Exception {
        CompoundTag root = terrain(12, "minecraft:air");
        root.putString("Status", "minecraft:carvers");
        CompoundTag structures = new CompoundTag();
        CompoundTag starts = new CompoundTag();
        starts.put("minecraft:village", new CompoundTag());
        structures.put("starts", starts);
        root.put("structures", structures);
        region("region", root, 2, false, true);
        var report = IplSavedRegionHeightScanner.scanReport(folder);
        assertEquals(Optional.of(IplAdaptiveStorageProfile.FULL_RANGE), report.bounds());
        assertEquals(1L, report.counts().get("fallback.proto_generation_state"));
        assertEquals(1L, report.counts().get("status.carvers"));
        root.remove("structures");
        root.putString("Status", "mod:unknown");
        region("region", root, 2, false, true);
        report = IplSavedRegionHeightScanner.scanReport(folder);
        assertEquals(IplAdaptiveStorageProfile.FULL_RANGE, report.contributions().get("fallback.unknown_status"));
        assertFalse(report.counts().containsKey("status.mod:unknown"), "Diagnostics have a fixed key vocabulary");
        root.putString("Status", "minecraft:full");
        root.put("below_zero_retrogen", new CompoundTag());
        region("region", root, 2, false, true);
        assertEquals(1L, IplSavedRegionHeightScanner.scanReport(folder).counts().get("fallback.retrogen"));
    }

    @Test void indexedAirMetadataProtectsAbsoluteSectionsUsingSavedYPos() throws Exception {
        CompoundTag root = terrain(0, "minecraft:air");
        ListTag postProcessing = new ListTag();
        for (int i = 0; i <= 120; i++) postProcessing.add(new ListTag());
        ((ListTag) postProcessing.get(120)).add(ShortTag.valueOf((short) 1));
        root.put("PostProcessing", postProcessing);
        CompoundTag upgrade = new CompoundTag();
        CompoundTag indices = new CompoundTag();
        indices.putIntArray("160", new int[] {1});
        upgrade.put("Indices", indices);
        root.put("UpgradeData", upgrade);
        region("region", root, 2, false, true);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-112, 656)), IplSavedRegionHeightScanner.scan(folder));
    }

    @Test void unknownCompressionAndBoundedDecoderFailuresNeverBecomeEmptyOccupancy() throws Exception {
        Path file = region("region", terrain(12, "minecraft:stone"), 2, false, true);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder,
            new IplSavedRegionHeightScanner.Limits(100, 100, 1 << 20, 16, 1 << 20)));
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder,
            new IplSavedRegionHeightScanner.Limits(100, 0, 1 << 20, 1 << 20, 1 << 20)));
        byte[] data = Files.readAllBytes(file);
        data[8196] = 127;
        Files.write(file, data);
        assertThrows(IOException.class, () -> IplSavedRegionHeightScanner.scan(folder));
    }

    private Path region(String kind, CompoundTag root, int codec, boolean external, boolean padded) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(RegionFileVersion.fromId(codec).wrap(encoded))) { NbtIo.write(root, output); }
        byte[] payload = encoded.toByteArray();
        Path dir = Files.createDirectories(folder.resolve(kind));
        int count = external ? 5 : payload.length + 5;
        int sectors = (count + 4095) / 4096;
        ByteBuffer bytes = ByteBuffer.allocate(8192 + (padded ? sectors * 4096 : count));
        bytes.putInt(0, (2 << 8) | sectors);
        bytes.position(8192);
        bytes.putInt(external ? 1 : payload.length + 1);
        bytes.put((byte) (codec | (external ? 128 : 0)));
        if (external) Files.write(dir.resolve("c.0.0.mcc"), payload);
        else bytes.put(payload);
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, bytes.array());
        return file;
    }

    private static CompoundTag terrain(int sectionY, String block) {
        CompoundTag root = versioned();
        root.putString("Status", "minecraft:full");
        root.putInt("yPos", -127);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", block);
        palette.add(entry);
        states.put("palette", palette);
        section.put("block_states", states);
        sections.add(section);
        root.put("sections", sections);
        return root;
    }
    private static CompoundTag versioned() {
        CompoundTag root = new CompoundTag(); root.putInt("DataVersion", 3955); return root;
    }
    private static CompoundTag entity(double y) {
        CompoundTag entity = new CompoundTag();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(0)); position.add(DoubleTag.valueOf(y)); position.add(DoubleTag.valueOf(0));
        entity.put("Pos", position);
        return entity;
    }
}
