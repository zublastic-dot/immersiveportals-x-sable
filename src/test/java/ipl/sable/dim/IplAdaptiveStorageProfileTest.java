package ipl.sable.dim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static ipl.sable.dim.IplAdaptiveStorageProfile.Bounds;
import static org.junit.jupiter.api.Assertions.*;

class IplAdaptiveStorageProfileTest {
    @TempDir Path folder;

    @Test void vanillaAndResolvedTallDimensionsUse24And38Sections() {
        Bounds vanilla = new Bounds(-64, 384).union(new Bounds(0, 256));
        assertEquals(24, IplAdaptiveStorageProfile.select(vanilla, Optional.empty(), Optional.empty()).sectionCount());
        Bounds tall = vanilla.union(new Bounds(-96, 608));
        Bounds selected = IplAdaptiveStorageProfile.select(tall, Optional.of(new Bounds(192, 32)), Optional.empty());
        assertEquals(new Bounds(-96, 608), selected);
        assertEquals(38, selected.sectionCount());
        assertEquals(19, (208 - selected.minY()) >> 4, "Raw Y208 keeps its absolute position in the smaller allocation");
    }

    @Test void savedRawExtremesExpandTheUnionWithoutUsingVisiblePoseAltitude() {
        Bounds parents = new Bounds(-96, 608);
        assertEquals(new Bounds(-160, 1024), IplAdaptiveStorageProfile.select(parents,
            Optional.of(new Bounds(-160, 1024)), Optional.empty()));
        Bounds extremes = new Bounds(-2032, 16).union(new Bounds(2016, 16));
        assertEquals(IplAdaptiveStorageProfile.FULL_RANGE,
            IplAdaptiveStorageProfile.select(parents, Optional.of(extremes), Optional.empty()));
        assertEquals(254, extremes.sectionCount());
    }

    @Test void restartKeepsTheRecordedFloorAndCanExpandForNewSavedData() throws Exception {
        Bounds initial = new Bounds(-96, 608);
        assertEquals(Optional.empty(), IplAdaptiveStorageProfile.read(folder));
        IplAdaptiveStorageProfile.persist(folder, initial);
        assertEquals(Optional.of(initial), IplAdaptiveStorageProfile.read(folder));
        Bounds restart = IplAdaptiveStorageProfile.select(new Bounds(0, 256), Optional.empty(),
            IplAdaptiveStorageProfile.read(folder));
        assertEquals(initial, restart);
        String before = Files.readString(folder.resolve(IplAdaptiveStorageProfile.FILE_NAME));
        IplAdaptiveStorageProfile.persist(folder, restart);
        assertEquals(before, Files.readString(folder.resolve(IplAdaptiveStorageProfile.FILE_NAME)));

        Bounds expanded = IplAdaptiveStorageProfile.select(new Bounds(0, 256), Optional.of(new Bounds(1024, 16)),
            IplAdaptiveStorageProfile.read(folder));
        IplAdaptiveStorageProfile.persist(folder, expanded);
        assertEquals(new Bounds(-96, 1136), IplAdaptiveStorageProfile.read(folder).orElseThrow());
        assertThrows(IOException.class, () -> IplAdaptiveStorageProfile.persist(folder, initial));
        assertEquals(Optional.of(expanded), IplAdaptiveStorageProfile.read(folder));
        try (var entries = Files.list(folder)) {
            assertEquals(1, entries.count(), "Atomic profile writes must not leave temporary files");
        }
    }

    @Test void invalidProfilesFailWithoutOverwritingTheEvidence() throws Exception {
        String[] invalid = {
            "{}", "{\"version\":2,\"min_y\":-96,\"height\":608}",
            "{\"version\":1,\"min_y\":-96,\"height\":608,\"height\":384}",
            "{\"version\":1,\"min_y\":-96,\"height\":608.5}",
            "{\"version\":1,\"min_y\":-96,\"height\":\"608\"}",
            "{\"version\":1,\"min_y\":-96,\"height\":608,\"extra\":1}",
            "{\"version\":1,\"min_y\":-96,\"height\":2147483648}",
            "{\"version\":1,\"min_y\":-96,\"height\":608} {}",
            "{\"version\":1,\"min_y\":-97,\"height\":608}",
            "{\"version\":1,\"min_y\":2016,\"height\":32}",
            " ".repeat(4097)
        };
        Path marker = folder.resolve(IplAdaptiveStorageProfile.FILE_NAME);
        for (String value : invalid) {
            Files.writeString(marker, value);
            assertThrows(IOException.class, () -> IplAdaptiveStorageProfile.read(folder), value);
            assertThrows(IOException.class, () -> IplAdaptiveStorageProfile.persist(folder, new Bounds(-96, 608)), value);
            assertEquals(value, Files.readString(marker), "Malformed marker must be retained for repair");
        }
    }

    @Test void rangeValidationRejectsOverflowAndClippingInsteadOfClamping() {
        int[][] invalid = {{-2048, 4096}, {-65, 384}, {-64, 0}, {-64, 385}, {2016, 32},
            {Integer.MAX_VALUE, 16}, {Integer.MIN_VALUE, 16}, {0, Integer.MAX_VALUE}};
        for (int[] pair : invalid) assertThrows(IllegalArgumentException.class, () -> new Bounds(pair[0], pair[1]));
        assertEquals(2032, new Bounds(2016, 16).maxY());
        assertEquals(1, new Bounds(-2032, 16).sectionCount());
    }
}
