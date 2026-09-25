package qouteall.imm_ptl.core.chunk_loading;

import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded local evidence, not a recovery mechanism or an alternative codec. */
final class ChunkPacketCaptureStore {
    static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    static final int MAX_REPORTS = 16;
    private final Path directory;
    private final int maxReports;
    private final int maxPayloadBytes;

    ChunkPacketCaptureStore(Path directory) {
        this(directory, MAX_REPORTS, MAX_PAYLOAD_BYTES);
    }

    ChunkPacketCaptureStore(Path directory, int maxReports, int maxPayloadBytes) {
        this.directory = directory;
        this.maxReports = maxReports;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    synchronized Path capture(ByteBuf buffer, int start, Map<String, Object> details) throws IOException {
        int length = buffer.writerIndex() - start;
        if (start < 0 || start > buffer.writerIndex() || length > maxPayloadBytes) {
            throw new IOException("Chunk packet capture exceeds the byte limit or has an invalid start index");
        }
        Files.createDirectories(directory);
        // Include incomplete directories in the persistent quota; a restart must
        // not turn repeated decode failures into unbounded disk usage.
        try (var entries = Files.list(directory)) {
            if (entries.filter(Files::isDirectory).limit(maxReports).count() >= maxReports) {
                throw new IOException("Chunk packet capture directory is full (" + maxReports + " reports)");
            }
        }
        byte[] bytes = new byte[length];
        // Absolute access preserves both indices even after a partial decode.
        buffer.getBytes(start, bytes);
        Path report = directory.resolve(UUID.randomUUID().toString());
        Files.createDirectory(report);
        Files.write(report.resolve("sections.bin"), bytes, StandardOpenOption.CREATE_NEW);
        Map<String, Object> metadata = new LinkedHashMap<>(details);
        metadata.put("schema_version", 1);
        metadata.put("captured_at", Instant.now().toString());
        metadata.put("bytes", length);
        metadata.put("sha256", sha256(bytes));
        metadata.put("original_reader_index", start);
        metadata.put("observed_reader_index", buffer.readerIndex());
        metadata.put("writer_index", buffer.writerIndex());
        Files.writeString(report.resolve("metadata.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n",
            StandardOpenOption.CREATE_NEW);
        return report;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
