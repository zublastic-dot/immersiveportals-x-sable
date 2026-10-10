package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IplHostingChunkStorageMigrationTest {
    @Test void fullRangeAnvilChunkShrinksWithoutMovingBlocksOrIndexedWork() {
        CompoundTag original = chunk(-127, 13);
        addIndexedWork(original, 140);
        IplHostingChunkStorageMigration.stampOwnedSave(original, -2032, 4064);
        CompoundTag before = original.copy();

        CompoundTag migrated = IplHostingChunkStorageMigration.prepareForLoad(original, -96, 608);
        assertEquals(before, original, "Preflight and migration leave the caller's NBT untouched");
        assertEquals(13, migrated.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getInt("Y"));
        assertEquals(before.getList("sections", Tag.TAG_COMPOUND).getCompound(0).get("block_states"),
            migrated.getList("sections", Tag.TAG_COMPOUND).getCompound(0).get("block_states"));
        assertEquals(38, migrated.getList("PostProcessing", Tag.TAG_LIST).size());
        assertEquals((short) 0x123, migrated.getList("PostProcessing", Tag.TAG_LIST).getList(19).getShort(0));
        assertArrayEquals(new int[] {0xabc}, migrated.getCompound("UpgradeData").getCompound("Indices").getIntArray("19"));
        assertEquals(1L, migrated.getCompound("CarvingMasks").getLongArray("AIR")[19 * 64]);
        assertEquals(before.getCompound("UpgradeData").get("neighbor_block_ticks"),
            migrated.getCompound("UpgradeData").get("neighbor_block_ticks"));
        assertFalse(migrated.contains("Heightmaps"));
        assertFalse(migrated.getBoolean("isLightOn"));
        for (Tag tag : migrated.getList("sections", Tag.TAG_COMPOUND)) {
            assertFalse(((CompoundTag) tag).contains("BlockLight"));
            assertFalse(((CompoundTag) tag).contains("SkyLight"));
        }
        assertTrue(migrated.getBoolean("shouldSave"));
        assertEquals(-6, migrated.getInt("yPos"));
        assertSame(migrated, IplHostingChunkStorageMigration.prepareForLoad(migrated, -96, 608),
            "Tagged unchanged profiles retain caches and avoid another NBT copy/reindex");
    }

    @Test void sameBitWidthMinShiftStillInvalidatesHeightmapsAndLight() {
        CompoundTag saved = chunk(-6, 13);
        addIndexedWork(saved, 19);
        IplHostingChunkStorageMigration.stampOwnedSave(saved, -96, 608);
        CompoundTag migrated = IplHostingChunkStorageMigration.prepareForLoad(saved, -80, 608);
        assertFalse(migrated.contains("Heightmaps"), "Both heights use identical bit width; vanilla cannot detect this shift");
        assertEquals((short) 0x123, migrated.getList("PostProcessing", Tag.TAG_LIST).getList(18).getShort(0));
        assertEquals(1L, migrated.getCompound("CarvingMasks").getLongArray("AIR")[18 * 64]);
        assertFalse(migrated.getBoolean("isLightOn"));
    }

    @Test void untaggedChunkUsesSavedYPosAndIsMarkedForOneTimeRewrite() {
        CompoundTag legacy = chunk(-4, 13);
        addIndexedWork(legacy, 17);
        CompoundTag migrated = IplHostingChunkStorageMigration.prepareForLoad(legacy, -96, 608);
        assertEquals((short) 0x123, migrated.getList("PostProcessing", Tag.TAG_LIST).getList(19).getShort(0));
        assertEquals(-96, migrated.getCompound(IplHostingChunkStorageMigration.PROFILE_TAG).getInt("min_y"));
        assertEquals(608, migrated.getCompound(IplHostingChunkStorageMigration.PROFILE_TAG).getInt("height"));
        assertFalse(legacy.contains(IplHostingChunkStorageMigration.PROFILE_TAG));
    }

    @Test void indexedAirPayloadsPreventLossBeforeAnyCopyOrMutation() {
        for (String mode : List.of("PostProcessing", "Indices", "CarvingMasks", "neighbor_block_ticks")) {
            CompoundTag saved = chunk(-127, 13);
            addIndexedWork(saved, 253);
            if (!mode.equals("PostProcessing")) saved.remove("PostProcessing");
            if (!mode.equals("Indices")) saved.getCompound("UpgradeData").remove("Indices");
            if (!mode.equals("CarvingMasks")) saved.remove("CarvingMasks");
            if (mode.equals("neighbor_block_ticks")) {
                saved.getCompound("UpgradeData").getList("neighbor_block_ticks", Tag.TAG_COMPOUND).getCompound(0).putInt("y", 2031);
            } else saved.getCompound("UpgradeData").remove("neighbor_block_ticks");
            CompoundTag before = saved.copy();
            assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(saved, -96, 608), mode);
            assertEquals(before, saved);
        }
    }

    @Test void malformedOriginsProfilesAndIndexAliasesFailClosed() {
        CompoundTag noOrigin = chunk(-127, 13);
        noOrigin.remove("yPos");
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(noOrigin, -96, 608));
        CompoundTag mismatch = chunk(-127, 13);
        IplHostingChunkStorageMigration.stampOwnedSave(mismatch, -2032, 4064);
        mismatch.putInt("yPos", -4);
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(mismatch, -96, 608));
        CompoundTag aliases = chunk(-127, 13);
        addIndexedWork(aliases, 140);
        aliases.getCompound("UpgradeData").getCompound("Indices").putIntArray("0140", new int[] {0});
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(aliases, -96, 608));
        CompoundTag badPost = chunk(-127, 13);
        ListTag values = new ListTag();
        values.add(StringTag.valueOf("not a list"));
        badPost.put("PostProcessing", values);
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(badPost, -96, 608));
    }

    @Test void airBiomesAndUnknownMetadataSurviveShrinkSaveReloadAndExpansion() {
        CompoundTag source = chunk(-127, 13);
        CompoundTag plains = airSection(-127, "minecraft:plains");
        plains.putString("example:section_note", "retained exactly");
        plains.putByteArray("SkyLight", new byte[] {1, 2, 3});
        CompoundTag desert = airSection(100, "minecraft:desert");
        source.getList("sections", Tag.TAG_COMPOUND).add(plains);
        source.getList("sections", Tag.TAG_COMPOUND).add(desert);
        IplHostingChunkStorageMigration.stampOwnedSave(source, -2032, 4064);
        CompoundTag before = source.copy();

        CompoundTag narrow = IplHostingChunkStorageMigration.prepareForLoad(source, -96, 608);
        assertEquals(before, source);
        assertEquals(1, narrow.getList("sections", Tag.TAG_COMPOUND).size());
        ListTag archive = narrow.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND);
        assertEquals(4, archive.size(), "Both real air sections and both light-padding records are retained");
        assertEquals("retained exactly", sectionAt(archive, -127).getString("example:section_note"));
        assertEquals(plains.get("biomes"), sectionAt(archive, -127).get("biomes"));
        assertFalse(sectionAt(archive, -127).contains("SkyLight"));

        CompoundTag saved = narrow.copy();
        IplHostingChunkStorageMigration.writeDeferredAirSections(saved, archive);
        IplHostingChunkStorageMigration.stampOwnedSave(saved, -96, 608);
        assertNotSame(archive, saved.get(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG));
        assertSame(saved, IplHostingChunkStorageMigration.prepareForLoad(saved, -96, 608));
        CompoundTag expanded = IplHostingChunkStorageMigration.prepareForLoad(saved, -2032, 4064);
        ListTag restored = expanded.getList("sections", Tag.TAG_COMPOUND);
        assertEquals(3, restored.size());
        assertEquals(desert, sectionAt(restored, 100));
        CompoundTag expectedPlains = plains.copy();
        expectedPlains.remove("SkyLight");
        assertEquals(expectedPlains, sectionAt(restored, -127));
        assertEquals(4, expanded.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND).size(),
            "Restored raw sections remain archived because vanilla reconstruction drops unknown metadata");
        CompoundTag reconstructed = expanded.copy();
        for (Tag entry : reconstructed.getList("sections", Tag.TAG_COMPOUND)) {
            ((CompoundTag) entry).remove("example:section_note"); // Vanilla serializes only its known fields.
        }
        reconstructed.remove(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG);
        IplHostingChunkStorageMigration.writeDeferredAirSections(reconstructed,
            expanded.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND));
        IplHostingChunkStorageMigration.stampOwnedSave(reconstructed, -2032, 4064);
        CompoundTag reloaded = IplHostingChunkStorageMigration.prepareForLoad(reconstructed, -2032, 4064);
        assertEquals("retained exactly", sectionAt(reloaded.getList(
            IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND), -127).getString("example:section_note"));
    }

    @Test void currentSectionWinsRestorationWithoutDiscardingTheArchivedVersion() {
        CompoundTag source = chunk(-127, 13);
        source.getList("sections", Tag.TAG_COMPOUND).add(airSection(100, "minecraft:desert"));
        CompoundTag narrow = IplHostingChunkStorageMigration.prepareForLoad(source, -96, 608);
        CompoundTag existing = airSection(100, "minecraft:forest");
        narrow.getList("sections", Tag.TAG_COMPOUND).add(existing);
        CompoundTag expanded = IplHostingChunkStorageMigration.prepareForLoad(narrow, -2032, 4064);
        assertEquals(existing, sectionAt(expanded.getList("sections", Tag.TAG_COMPOUND), 100));
        assertEquals(existing, sectionAt(
            expanded.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND), 100));
    }

    @Test void activeEditsAndRemovalCannotBeResurrectedByAnOlderArchive() {
        CompoundTag source = chunk(-127, 13);
        CompoundTag desert = airSection(100, "minecraft:desert");
        desert.putString("example:metadata", "survives vanilla reconstruction");
        source.getList("sections", Tag.TAG_COMPOUND).add(desert);
        CompoundTag narrow = IplHostingChunkStorageMigration.prepareForLoad(source, -96, 608);
        CompoundTag wide = IplHostingChunkStorageMigration.prepareForLoad(narrow, -2032, 4064);
        ListTag sections = wide.getList("sections", Tag.TAG_COMPOUND);
        sections.removeIf(entry -> ((CompoundTag) entry).getInt("Y") == 100);
        sections.add(airSection(100, "minecraft:forest")); // Current save drops unknown metadata.
        CompoundTag narrowAgain = IplHostingChunkStorageMigration.prepareForLoad(wide, -96, 608);
        CompoundTag wideAgain = IplHostingChunkStorageMigration.prepareForLoad(narrowAgain, -2032, 4064);
        CompoundTag restored = sectionAt(wideAgain.getList("sections", Tag.TAG_COMPOUND), 100);
        assertEquals(airSection(100, "minecraft:forest").get("biomes"), restored.get("biomes"));
        assertEquals("survives vanilla reconstruction", restored.getString("example:metadata"));

        wideAgain.getList("sections", Tag.TAG_COMPOUND).removeIf(entry -> ((CompoundTag) entry).getInt("Y") == 100);
        CompoundTag sameProfile = IplHostingChunkStorageMigration.prepareForLoad(wideAgain, -2032, 4064);
        assertTrue(sameProfile.getList("sections", Tag.TAG_COMPOUND).stream()
            .noneMatch(entry -> ((CompoundTag) entry).getInt("Y") == 100));
        CompoundTag removedNarrow = IplHostingChunkStorageMigration.prepareForLoad(sameProfile, -96, 608);
        CompoundTag removedWide = IplHostingChunkStorageMigration.prepareForLoad(removedNarrow, -2032, 4064);
        CompoundTag metadataOnly = sectionAt(removedWide.getList("sections", Tag.TAG_COMPOUND), 100);
        assertFalse(metadataOnly.contains("biomes"), "Removed active biomes cannot reappear from a stale archive");
        assertFalse(metadataOnly.contains("block_states"));
        assertEquals("survives vanilla reconstruction", metadataOnly.getString("example:metadata"));
    }

    @Test void invalidDeferredPayloadFailsBeforeMutation() {
        CompoundTag source = chunk(-127, 13);
        ListTag deferred = new ListTag();
        deferred.add(source.getList("sections", Tag.TAG_COMPOUND).getCompound(0).copy());
        source.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, deferred);
        CompoundTag before = source.copy();
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.prepareForLoad(source, -96, 608));
        assertEquals(before, source);
        deferred.clear();
        deferred.add(airSection(100, "minecraft:desert"));
        deferred.add(airSection(100, "minecraft:forest"));
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.validateDeferredAirSections(source));
    }

    @Test void oldLightPaddingCannotHideADeferredBiomeWhenProfileExpands() {
        CompoundTag source = chunk(-127, 13);
        CompoundTag desert = airSection(-7, "minecraft:desert");
        source.getList("sections", Tag.TAG_COMPOUND).add(desert);
        CompoundTag narrow = IplHostingChunkStorageMigration.prepareForLoad(source, -96, 608);
        CompoundTag lightPadding = new CompoundTag();
        lightPadding.putInt("Y", -7);
        lightPadding.putByteArray("BlockLight", new byte[2048]);
        narrow.getList("sections", Tag.TAG_COMPOUND).add(lightPadding);
        CompoundTag expanded = IplHostingChunkStorageMigration.prepareForLoad(narrow, -2032, 4064);
        assertEquals(desert, sectionAt(expanded.getList("sections", Tag.TAG_COMPOUND), -7));
    }

    @Test void deferredMultiBiomeDataMustDecodeAllQuartEntries() {
        CompoundTag source = chunk(-127, 13);
        CompoundTag section = airSection(100, "minecraft:desert");
        CompoundTag biomes = section.getCompound("biomes");
        biomes.getList("palette", Tag.TAG_STRING).add(StringTag.valueOf("minecraft:forest"));
        ListTag deferred = new ListTag();
        deferred.add(section);
        source.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, deferred);
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.validateDeferredAirSections(source));
        biomes.putLongArray("data", new long[] {-1L});
        assertDoesNotThrow(() -> IplHostingChunkStorageMigration.validateDeferredAirSections(source));
        biomes.getList("palette", Tag.TAG_STRING).add(StringTag.valueOf("minecraft:plains"));
        biomes.putLongArray("data", new long[] {-1L, -1L});
        assertThrows(IllegalStateException.class, () -> IplHostingChunkStorageMigration.validateDeferredAirSections(source));
    }

    @Test void serializerArgumentIsReplacedBeforeReturnLightHooksAndOrdinaryLevelsBypass() throws Exception {
        ClassNode mixin = read("ipl/sable/mixin/IplHostingChunkSerializationMixin");
        var migrate = mixin.methods.stream().filter(m -> m.name.equals("iplsable$migrateHostingChunk")).findFirst().orElseThrow();
        AnnotationNode annotation = migrate.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/ModifyVariable;")).findFirst().orElseThrow();
        assertEquals(List.of("read"), value(annotation, "method"));
        assertEquals(4, value(annotation, "index"));
        assertEquals(true, value(annotation, "argsOnly"));
        assertEquals(1, value(annotation, "require"));
        AnnotationNode site = assertInstanceOf(AnnotationNode.class, value(annotation, "at"));
        assertEquals("HEAD", value(site, "value"));
        for (var method : mixin.methods) {
            if (!method.name.startsWith("iplsable$")) continue;
            int guard = -1, action = -1;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("ipl/sable/dim/IplDimAgnostic") && call.name.equals("isHostingLevel")) guard = method.instructions.indexOf(call);
                if (call.owner.equals("ipl/sable/dim/IplHostingChunkStorageMigration")
                    || call.owner.equals("ipl/sable/dim/IplDeferredAirSections")) action = method.instructions.indexOf(call);
            }
            assertTrue(guard >= 0 && action > guard, "Ordinary-world guard precedes either read or write transformation");
        }
        assertTrue(read("net/minecraft/world/level/chunk/storage/ChunkSerializer").methods.stream().anyMatch(m -> m.name.equals("read")
            && m.desc.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
                + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;"
                + "Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;")));
    }

    private static CompoundTag chunk(int minSection, int blockSection) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", 3955);
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("xPos", 0);
        chunk.putInt("yPos", minSection);
        chunk.putInt("zPos", 0);
        ListTag sections = new ListTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) blockSection);
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:obsidian");
        palette.add(state);
        states.put("palette", palette);
        section.put("block_states", states);
        section.putByteArray("BlockLight", new byte[2048]);
        sections.add(section);
        for (int padding : new int[] {-128, 127}) {
            CompoundTag lightOnly = new CompoundTag();
            lightOnly.putInt("Y", padding); // ScalableLux's exact light-only save representation
            lightOnly.putByteArray("SkyLight", new byte[2048]);
            sections.add(lightOnly);
        }
        chunk.put("sections", sections);
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("WORLD_SURFACE", new long[43]);
        chunk.put("Heightmaps", heightmaps);
        chunk.putBoolean("isLightOn", true);
        return chunk;
    }

    private static CompoundTag airSection(int y, String biome) {
        CompoundTag section = new CompoundTag();
        section.putInt("Y", y);
        CompoundTag states = new CompoundTag();
        ListTag blocks = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        blocks.add(air);
        states.put("palette", blocks);
        section.put("block_states", states);
        CompoundTag biomes = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(StringTag.valueOf(biome));
        biomes.put("palette", palette);
        section.put("biomes", biomes);
        return section;
    }

    private static CompoundTag sectionAt(ListTag sections, int y) {
        return sections.stream().map(CompoundTag.class::cast).filter(section -> section.getInt("Y") == y).findFirst().orElseThrow();
    }

    private static void addIndexedWork(CompoundTag chunk, int index) {
        ListTag processing = new ListTag();
        for (int i = 0; i <= index; i++) processing.add(new ListTag());
        processing.getList(index).add(ShortTag.valueOf((short) 0x123));
        chunk.put("PostProcessing", processing);
        CompoundTag upgrade = new CompoundTag();
        CompoundTag indices = new CompoundTag();
        indices.putIntArray(Integer.toString(index), new int[] {0xabc});
        upgrade.put("Indices", indices);
        ListTag ticks = new ListTag();
        CompoundTag tick = new CompoundTag();
        tick.putInt("y", 208);
        ticks.add(tick);
        upgrade.put("neighbor_block_ticks", ticks);
        chunk.put("UpgradeData", upgrade);
        CompoundTag masks = new CompoundTag();
        long[] mask = new long[index * 64 + 1];
        mask[index * 64] = 1;
        masks.putLongArray("AIR", mask);
        chunk.put("CarvingMasks", masks);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private static ClassNode read(String path) throws Exception {
        try (var input = IplHostingChunkStorageMigrationTest.class.getResourceAsStream("/" + path + ".class")) {
            assertNotNull(input, path);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
