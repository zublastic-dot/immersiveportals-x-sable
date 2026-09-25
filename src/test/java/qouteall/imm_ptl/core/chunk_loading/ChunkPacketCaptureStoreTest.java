package qouteall.imm_ptl.core.chunk_loading;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPacketCaptureStoreTest {
    @TempDir Path root;

    @Test void failedPartialReadPreservesOriginalBytesAndLiveBufferIndices() throws Exception {
        var buffer = Unpooled.wrappedBuffer(new byte[]{99, 1, 2, 3, 4});
        try {
            buffer.readerIndex(4);
            Path report = new ChunkPacketCaptureStore(root).capture(buffer, 1, Map.of("kind", "test"));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(report.resolve("sections.bin")));
            assertEquals(4, buffer.readerIndex());
            assertEquals(5, buffer.writerIndex());
            var metadata = JsonParser.parseString(Files.readString(report.resolve("metadata.json"))).getAsJsonObject();
            assertEquals(1, metadata.get("original_reader_index").getAsInt());
            assertEquals(4, metadata.get("observed_reader_index").getAsInt());
            assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", metadata.get("sha256").getAsString());
        } finally { buffer.release(); }
    }

    @Test void byteLimitAndInvalidIndicesAreRejectedWithoutChangingTheBuffer() {
        var buffer = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        try {
            var store = new ChunkPacketCaptureStore(root, 2, 2);
            assertThrows(IOException.class, () -> store.capture(buffer, 0, Map.of()));
            assertThrows(IOException.class, () -> store.capture(buffer, -1, Map.of()));
            assertThrows(IOException.class, () -> store.capture(buffer, 4, Map.of()));
            assertEquals(0, buffer.readerIndex());
            assertEquals(3, buffer.writerIndex());
        } finally { buffer.release(); }
    }

    @Test void quotaSurvivesStoreRecreationAndKeepsExistingEvidence() throws Exception {
        var buffer = Unpooled.wrappedBuffer(new byte[]{5});
        try {
            var first = new ChunkPacketCaptureStore(root, 1, 2).capture(buffer, 0, Map.of());
            assertThrows(IOException.class, () -> new ChunkPacketCaptureStore(root, 1, 2).capture(buffer, 0, Map.of()));
            assertArrayEquals(new byte[]{5}, Files.readAllBytes(first.resolve("sections.bin")));
        } finally { buffer.release(); }
    }

    @Test void incompleteReportAlsoOccupiesThePersistentQuota() throws Exception {
        Files.createDirectory(root.resolve("incomplete"));
        var buffer = Unpooled.wrappedBuffer(new byte[]{5});
        try {
            assertThrows(IOException.class, () -> new ChunkPacketCaptureStore(root, 1, 2).capture(buffer, 0, Map.of()));
        } finally { buffer.release(); }
    }
}
