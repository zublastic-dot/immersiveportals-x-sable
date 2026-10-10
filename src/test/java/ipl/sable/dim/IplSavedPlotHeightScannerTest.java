package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IplSavedPlotHeightScannerTest {
    @TempDir Path folder;

    @Test void missingSaveHasNoOccupancyAndCreatesNothing() throws Exception {
        Path missing = folder.resolve("absent");
        assertEquals(Optional.empty(), IplSavedPlotHeightScanner.scan(missing));
        assertFalse(Files.exists(missing));
    }

    @Test void scansSparseCompressedAndRawRecordsWithoutRewritingAnySave() throws Exception {
        Path compressed = storage("r.0.0.0.slvls", root(139), true, false, 0);
        Path raw = storage("r.-1.2.0.slvls", root(121, 158), false, false, 3);
        byte[] beforeCompressed = Files.readAllBytes(compressed);
        byte[] beforeRaw = Files.readAllBytes(raw);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-96, 608)),
            IplSavedPlotHeightScanner.scan(folder));
        assertArrayEquals(beforeCompressed, Files.readAllBytes(compressed));
        assertArrayEquals(beforeRaw, Files.readAllBytes(raw));
    }

    @Test void scansExternalRecordsAndRequiresHoldingPointersToResolve() throws Exception {
        Path packed = storage("r.0.0.2.slvls", root(139), true, true, 7);
        Path external = folder.resolve("sublevels/r.0.0.2.s/7.slvl");
        byte[] original = Files.readAllBytes(external);
        CompoundTag holding = new CompoundTag();
        holding.putIntArray("pointers", new int[] {(2 << 16) | 7});
        storage("r.0.0.slvlr", holding, true, false, 1);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)),
            IplSavedPlotHeightScanner.scan(folder));
        assertArrayEquals(original, Files.readAllBytes(external));
        Files.delete(packed);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder),
            "An orphan external payload cannot replace a missing indexed record");
        assertArrayEquals(original, Files.readAllBytes(external));
    }

    @Test void orphanExternalPayloadAlsoProtectsItsOccupiedExtent() throws Exception {
        Path dir = Files.createDirectories(folder.resolve("sublevels/r.0.0.0.s"));
        Files.write(dir.resolve("0.slvl"), encode(root(0, 253), true));
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-2032, 4064)),
            IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void sparsePositionedMetadataExpandsBoundsWithoutSections() throws Exception {
        CompoundTag root = root();
        CompoundTag chunk = root.getCompound("plot").getCompound("chunks").getCompound("0");
        ListTag ticks = new ListTag();
        CompoundTag tick = new CompoundTag();
        tick.putInt("y", -97);
        ticks.add(tick);
        chunk.put("block_ticks", ticks);
        storage("r.0.0.0.slvls", root, true, false, 0);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(-112, 16)),
            IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void corruptOrdinaryPayloadCannotBeIgnoredWhenSableRecordsAreValid() throws Exception {
        Files.createDirectories(folder.resolve("entities"));
        Files.write(folder.resolve("entities/r.0.0.mca"), new byte[] {1});
        storage("r.0.0.0.slvls", root(139), true, false, 0);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void profileMarkerNeverCreatesAnOccupancyFloor() throws Exception {
        Files.writeString(folder.resolve("ipl-storage-profile.json"), "{\"min_y\":-2032,\"height\":4064}");
        assertTrue(IplSavedPlotHeightScanner.scan(folder).isEmpty());
    }

    @Test void missingExternalUnknownFlagsAndTruncatedPayloadAllFailClosed() throws Exception {
        Path file = storage("r.0.0.0.slvls", root(139), true, true, 0);
        Path external = folder.resolve("sublevels/r.0.0.0.s/0.slvl");
        Files.delete(external);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
        byte[] invalid = Files.readAllBytes(file);
        invalid[4100] = 4;
        Files.write(file, invalid);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
        invalid[4100] = 0;
        ByteBuffer.wrap(invalid).putInt(4096, 4096);
        Files.write(file, invalid);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void durableShortFinalSectorNeedsPayloadBytesButNotUnusedPadding() throws Exception {
        Path file = storage("r.0.0.0.slvls", root(139), true, false, 0);
        byte[] padded = Files.readAllBytes(file);
        int payloadEnd = 4096 + 4 + ByteBuffer.wrap(padded).getInt(4096);
        byte[] unpadded = java.util.Arrays.copyOf(padded, payloadEnd);
        Files.write(file, unpadded);
        assertEquals(Optional.of(new IplAdaptiveStorageProfile.Bounds(192, 16)),
            IplSavedPlotHeightScanner.scan(folder));
        assertArrayEquals(unpadded, Files.readAllBytes(file));
        Files.write(file, java.util.Arrays.copyOf(unpadded, unpadded.length - 1));
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
    }

    @Test void overlappingSpansAndMalformedLatePlotCannotBeSkipped() throws Exception {
        Path valid = storage("r.0.0.0.slvls", root(139), true, false, 0);
        byte[] bytes = Files.readAllBytes(valid);
        ByteBuffer.wrap(bytes).putInt(4, ByteBuffer.wrap(bytes).getInt(0));
        Files.write(valid, bytes);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder));
        ByteBuffer.wrap(bytes).putInt(4, 0);
        Files.write(valid, bytes);
        CompoundTag malformed = root(139);
        malformed.getCompound("plot").getCompound("chunks").getCompound("0")
            .getCompound("sections").put("254", new CompoundTag());
        storage("r.0.0.1.slvls", malformed, true, false, 0);
        assertThrows(IllegalStateException.class, () -> IplSavedPlotHeightScanner.scan(folder));
        assertArrayEquals(bytes, Files.readAllBytes(valid));
    }

    @Test void byteRecordAndDecodedNbtLimitsFailClosed() throws Exception {
        storage("r.0.0.0.slvls", root(139), true, false, 0);
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder,
            new IplSavedPlotHeightScanner.Limits(100, 0, 1 << 20, 1 << 20, 1 << 20)));
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder,
            new IplSavedPlotHeightScanner.Limits(100, 100, 1 << 20, 16, 1 << 20)));
        assertThrows(IOException.class, () -> IplSavedPlotHeightScanner.scan(folder,
            new IplSavedPlotHeightScanner.Limits(100, 100, 1 << 20, 1 << 20, 4096)));
    }

    private Path storage(String name, CompoundTag root, boolean compressed, boolean external, int slot) throws Exception {
        Path sublevels = Files.createDirectories(folder.resolve("sublevels"));
        boolean holding = name.endsWith(".slvlr");
        int sectorSize = holding ? 128 : 4096;
        byte[] payload = encode(root, compressed);
        int bytes = external ? 5 : 5 + payload.length;
        int sectors = (bytes + sectorSize - 1) / sectorSize;
        ByteBuffer file = ByteBuffer.allocate(4096 + sectors * sectorSize);
        file.putInt(slot * 4, ((4096 / sectorSize) << 8) | sectors);
        file.position(4096);
        file.putInt(external ? 1 : payload.length + 1);
        file.put((byte) (external ? 0x10 : 0));
        if (!external) file.put(payload);
        else {
            String stem = name.substring(0, name.lastIndexOf('.'));
            Path directory = Files.createDirectories(sublevels.resolve(stem + (holding ? ".r" : ".s")));
            Files.write(directory.resolve(slot + ".slvl"), payload);
        }
        Path path = sublevels.resolve(name);
        Files.write(path, file.array());
        return path;
    }

    private static byte[] encode(CompoundTag root, boolean compressed) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (compressed) NbtIo.writeCompressed(root, bytes);
        else NbtIo.write(root, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static CompoundTag root(int... indices) {
        CompoundTag sections = new CompoundTag();
        for (int index : indices) sections.put(Integer.toString(index), new CompoundTag());
        CompoundTag chunk = new CompoundTag();
        chunk.put("sections", sections);
        CompoundTag chunks = new CompoundTag();
        chunks.put("0", chunk);
        CompoundTag plot = new CompoundTag();
        plot.put("chunks", chunks);
        CompoundTag root = new CompoundTag();
        root.put("plot", IplPlotStorageMigration.stampSave(plot, -2032, 4064));
        root.putString("pose-sentinel", "untouched by scan");
        return root;
    }
}
