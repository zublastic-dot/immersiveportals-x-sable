package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IplPlotStorageMigrationTest {
    @Test void legacySectionsKeepAbsoluteYAndPositionPayloadsAcrossExpansion() {
        CompoundTag saved = plot(0, 17, 23);
        CompoundTag oldChunk = chunk(saved);
        oldChunk.put("block_entities", positions(208));
        oldChunk.put("block_ticks", positions(-64));
        oldChunk.put("fluid_ticks", positions(319));
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("WORLD_SURFACE", new long[] {42});
        oldChunk.put("heightmaps", heightmaps);
        saved.putString("unrelated", "preserved");
        saved.putInt("data_version", 1);
        CompoundTag before = saved.copy();

        CompoundTag migrated = IplPlotStorageMigration.prepareForLoad(saved, -2032, 4064);
        CompoundTag sections = chunk(migrated).getCompound("sections");
        assertEquals(3, sections.size());
        assertEquals("section-0", sections.getCompound("123").getString("sentinel"));
        assertEquals("section-17", sections.getCompound("140").getString("sentinel"));
        assertEquals("section-23", sections.getCompound("146").getString("sentinel"));
        assertEquals(oldChunk.get("block_entities"), chunk(migrated).get("block_entities"));
        assertEquals(oldChunk.get("block_ticks"), chunk(migrated).get("block_ticks"));
        assertEquals(oldChunk.get("fluid_ticks"), chunk(migrated).get("fluid_ticks"));
        assertFalse(chunk(migrated).contains("heightmaps"));
        assertEquals("preserved", migrated.getString("unrelated"));
        assertEquals(1, migrated.getInt("data_version"));
        assertEquals(before, saved, "Caller-owned saved NBT must never be rewritten");
        assertEquals(migrated, IplPlotStorageMigration.prepareForLoad(migrated, -2032, 4064));
    }

    @Test void taggedFullRangeRoundTripsBothExtremeSectionsWithoutAnotherShift() {
        CompoundTag saved = plot(0, 253);
        chunk(saved).put("block_entities", positions(-2032, 2031));
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("WORLD_SURFACE", new long[] {7});
        chunk(saved).put("heightmaps", heightmaps);
        CompoundTag tagged = IplPlotStorageMigration.stampSave(saved, -2032, 4064);
        assertFalse(saved.contains(IplPlotStorageMigration.PROFILE_TAG));
        CompoundTag loaded = IplPlotStorageMigration.prepareForLoad(tagged, -2032, 4064);
        assertEquals(tagged, loaded);
        assertNotSame(tagged, loaded);
        assertTrue(chunk(loaded).contains("heightmaps"));
    }

    @Test void arbitraryLegalTaggedProfilesRemapByAbsoluteSectionY() {
        CompoundTag saved = IplPlotStorageMigration.stampSave(plot(0, 37), -96, 608);
        CompoundTag migrated = IplPlotStorageMigration.prepareForLoad(saved, -2032, 4064);
        assertEquals("section-0", chunk(migrated).getCompound("sections").getCompound("121").getString("sentinel"));
        assertEquals("section-37", chunk(migrated).getCompound("sections").getCompound("158").getString("sentinel"));
        CompoundTag highestSingleSection = IplPlotStorageMigration.stampSave(plot(0), 2016, 16);
        assertTrue(chunk(IplPlotStorageMigration.prepareForLoad(highestSingleSection, -2032, 4064))
            .getCompound("sections").contains("253"));
    }

    @Test void legacyIndicesOutsideKnownHostingLayoutAreRejectedWithoutMutation() {
        CompoundTag saved = plot(17);
        CompoundTag badChunk = new CompoundTag();
        CompoundTag sections = new CompoundTag();
        sections.put("24", new CompoundTag());
        badChunk.put("sections", sections);
        saved.getCompound("chunks").put("later-malformed-chunk", badChunk);
        CompoundTag before = saved.copy();
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(saved, -2032, 4064));
        assertEquals(before, saved);
    }

    @Test void aliasesAndMalformedSectionPayloadsCannotOverwriteOrDisappear() {
        CompoundTag aliases = plot(1);
        chunk(aliases).getCompound("sections").put("01", new CompoundTag());
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(aliases, -2032, 4064));
        CompoundTag malformed = plot(1);
        chunk(malformed).getCompound("sections").putString("1", "not a section");
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(malformed, -2032, 4064));
    }

    @Test void narrowingCannotDiscardSectionsOrPositionedPayloads() {
        CompoundTag saved = IplPlotStorageMigration.stampSave(plot(0, 253), -2032, 4064);
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(saved, -64, 384));
        CompoundTag orphanedPosition = plot(17);
        chunk(orphanedPosition).put("block_ticks", positions(400));
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(orphanedPosition, -2032, 4064));
    }

    @Test void unknownOrMalformedProfilesAreRejected() {
        CompoundTag unknown = IplPlotStorageMigration.stampSave(plot(17), -64, 384);
        unknown.getCompound(IplPlotStorageMigration.PROFILE_TAG).putInt("version", 2);
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(unknown, -2032, 4064));
        CompoundTag incomplete = plot(17);
        incomplete.put(IplPlotStorageMigration.PROFILE_TAG, new CompoundTag());
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(incomplete, -2032, 4064));
        assertThrows(IllegalStateException.class, () -> IplPlotStorageMigration.stampSave(plot(0), -2048, 4096));
        assertThrows(IllegalStateException.class, () -> IplPlotStorageMigration.stampSave(plot(0), -65, 384));
        assertThrows(IllegalStateException.class, () -> IplPlotStorageMigration.stampSave(plot(0), 2016, 32));
    }

    @Test void sparseFullRangeShrinksWithoutMovingAbsoluteYOrMetadata() {
        CompoundTag saved = IplPlotStorageMigration.stampSave(plot(121, 139, 158), -2032, 4064);
        chunk(saved).put("block_entities", positions(-96, 511));
        chunk(saved).put("block_ticks", positions(208));
        chunk(saved).put("fluid_ticks", positions(-1));
        chunk(saved).putString("neoforge:attachments", "opaque-preserved");
        CompoundTag before = saved.copy();
        CompoundTag shrunk = IplPlotStorageMigration.prepareForLoad(saved, -96, 608);
        assertEquals(before, saved);
        assertEquals(java.util.Set.of("0", "18", "37"), chunk(shrunk).getCompound("sections").getAllKeys());
        for (String key : new String[] {"block_entities", "block_ticks", "fluid_ticks", "neoforge:attachments"}) {
            assertEquals(chunk(saved).get(key), chunk(shrunk).get(key));
        }
        assertEquals(shrunk, IplPlotStorageMigration.prepareForLoad(shrunk, -96, 608));
        assertEquals(new IplAdaptiveStorageProfile.Bounds(-96, 608),
            IplPlotStorageMigration.occupiedBounds(saved).orElseThrow());
    }

    @Test void sparseScanDoesNotKeepOldAllocationFloorAndIncludesOrphanPositionPayloads() {
        CompoundTag saved = IplPlotStorageMigration.stampSave(plot(139), -2032, 4064);
        assertEquals(new IplAdaptiveStorageProfile.Bounds(192, 16),
            IplPlotStorageMigration.occupiedBounds(saved).orElseThrow());
        chunk(saved).put("block_ticks", positions(-1000));
        chunk(saved).put("block_entities", positions(1000));
        assertEquals(new IplAdaptiveStorageProfile.Bounds(-1008, 2016),
            IplPlotStorageMigration.occupiedBounds(saved).orElseThrow());
        assertTrue(IplPlotStorageMigration.occupiedBounds(plot()).isEmpty());
    }

    @Test void auxiliaryLightsConstrainShrinkingWithoutMovingPackedPosition() {
        CompoundTag saved = IplPlotStorageMigration.stampSave(plot(139), -2032, 4064);
        ListTag lights = new ListTag();
        CompoundTag light = new CompoundTag();
        light.putLong("pos", 2031L); // zero X/Z, signed twelve-bit Y
        light.putByte("level", (byte) 12);
        lights.add(light);
        chunk(saved).put("neoforge:aux_lights", lights);
        assertEquals(new IplAdaptiveStorageProfile.Bounds(192, 1840),
            IplPlotStorageMigration.occupiedBounds(saved).orElseThrow());
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.prepareForLoad(saved, -96, 608));
        light.putLong("pos", -96L & 0xfffL);
        CompoundTag migrated = IplPlotStorageMigration.prepareForLoad(saved, -96, 608);
        assertEquals(lights, chunk(migrated).get("neoforge:aux_lights"));
    }

    @Test void ownedSaveStampsWithoutDeepCopyButFailureNeverMutates() {
        CompoundTag owned = plot(139);
        assertSame(owned, IplPlotStorageMigration.stampOwnedSave(owned, -2032, 4064));
        CompoundTag invalid = plot(254);
        CompoundTag before = invalid.copy();
        assertThrows(IllegalStateException.class,
            () -> IplPlotStorageMigration.stampOwnedSave(invalid, -2032, 4064));
        assertEquals(before, invalid);
    }

    private static CompoundTag plot(int... indices) {
        CompoundTag plot = new CompoundTag();
        CompoundTag chunks = new CompoundTag();
        CompoundTag chunk = new CompoundTag();
        CompoundTag sections = new CompoundTag();
        for (int index : indices) {
            CompoundTag section = new CompoundTag();
            section.putString("sentinel", "section-" + index);
            sections.put(Integer.toString(index), section);
        }
        chunk.put("sections", sections);
        chunks.put("0", chunk);
        plot.put("chunks", chunks);
        return plot;
    }

    private static CompoundTag chunk(CompoundTag plot) { return plot.getCompound("chunks").getCompound("0"); }

    private static ListTag positions(int... ys) {
        ListTag result = new ListTag();
        for (int y : ys) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", 20481033);
            entry.putInt("y", y);
            entry.putInt("z", 20481032);
            entry.putString("id", "test:preserved");
            result.add(entry);
        }
        return result;
    }
}
