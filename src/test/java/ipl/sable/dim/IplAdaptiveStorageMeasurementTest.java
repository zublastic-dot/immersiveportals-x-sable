package ipl.sable.dim;

import com.google.gson.Gson;
import com.mojang.serialization.Lifecycle;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.Unpooled;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in local diagnostic audit. Reads a copied dimension folder; never writes a world or profile. */
public class IplAdaptiveStorageMeasurementTest {
    private static final String SNAPSHOT_ENV = "IPLSABLE_HEIGHT_AUDIT_SNAPSHOT";
    private static final Gson JSON = new Gson();
    private static volatile Object retainedSections;
    private static volatile long serializedSink;

    @Test void auditExternalDiagnosticSnapshotAndEmptySectionCosts() throws Exception {
        String configured = System.getenv(SNAPSHOT_ENV);
        assumeTrue(configured != null && !configured.isBlank(), "Set " + SNAPSHOT_ENV + " to a copied hosting dimension folder");
        audit(Path.of(configured));
    }

    /** May also be invoked with the ordinary compiled test runtime classpath. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one copied hosting-dimension folder path");
        audit(Path.of(args[0]));
    }

    private static void audit(Path input) throws Exception {
        Path folder = input.toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS), "Snapshot folder must exist without a symlink");
        Inventory before = inventory(folder);
        assertTrue(before.storageRecords.values().stream().mapToLong(Long::longValue).sum() > 0,
            "Snapshot must contain nonempty Sable/Anvil headers or external records; do not silently audit the wrong folder");
        PortalBlockTestBootstrap.initialize();
        List<Long> scanNanos = new ArrayList<>();
        Optional<IplAdaptiveStorageProfile.Bounds> bounds = null;
        for (int repetition = 0; repetition < 3; repetition++) {
            long started = System.nanoTime();
            var current = IplSavedPlotHeightScanner.scan(folder);
            scanNanos.add(System.nanoTime() - started);
            if (bounds != null) assertEquals(bounds, current, "Repeated scans must select the same occupied bounds");
            bounds = current;
        }
        var recordedProfile = IplAdaptiveStorageProfile.read(folder);
        long archiveStarted = System.nanoTime();
        var archiveAudit = IplSavedRegionHeightScanner.auditDeferredArchives(folder, -96, 608);
        long archiveNanos = System.nanoTime() - archiveStarted;
        assertEquals(before, inventory(folder), "Diagnostic input contents/metadata changed during audit");
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("kind", "saved-plot-scan");
        scan.put("snapshot", folder.toString());
        scan.put("inputFiles", before.files.size());
        scan.put("inputBytes", before.bytes);
        scan.put("inputManifestSha256", digest(JSON.toJson(before.files).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        scan.put("headerAndExternalCounts", before.storageRecords);
        scan.put("occupiedBounds", bounds.orElse(null));
        scan.put("occupiedSections", bounds.map(IplAdaptiveStorageProfile.Bounds::sectionCount).orElse(0));
        scan.put("recordedProfile", recordedProfile.orElse(null));
        scan.put("deferredArchiveAudit", archiveAudit);
        scan.put("deferredArchiveAuditNanos", archiveNanos);
        scan.put("archiveAuditCandidateScope", "Retained tall Lab profile [-96,512); candidate supplied only to this read-only audit, never persisted");
        scan.put("archiveFootprintLimitation", "Exact codec bytes projected across every copied terrain record, not observed concurrently loaded heap. Array estimate excludes holder objects and transient encode/decode NBT; the actual loaded subset is unknown.");
        scan.put("scanNanos", scanNanos);
        scan.put("scanMillis", scanNanos.stream().map(value -> value / 1_000_000.0).toList());
        scan.put("inputUnchanged", true);
        scan.put("coldDiskMeasurement", false);
        scan.put("limitation", "Input hashing precedes scans; OS caches may be warm. Counts are header slots and external files, not unique ships.");
        emit(scan);

        Registry<Biome> biomes = vanillaBiomes();
        var management = ManagementFactory.getThreadMXBean();
        assertInstanceOf(ThreadMXBean.class, management, "Allocated-byte accounting requires the HotSpot ThreadMXBean");
        ThreadMXBean allocations = (ThreadMXBean) management;
        assertTrue(allocations.isThreadAllocatedMemorySupported(), "Actual allocation accounting is unavailable");
        boolean wasEnabled = allocations.isThreadAllocatedMemoryEnabled();
        if (!wasEnabled) allocations.setThreadAllocatedMemoryEnabled(true);
        try {
            // Load serializers/Netty classes and warm both sizes before measuring.
            for (int warmup = 0; warmup < 64; warmup++) for (int count : new int[]{38, 254}) {
                LevelChunkSection[] sections = emptySections(biomes, count);
                retainedSections = sections;
                serializedSink = serialize(sections);
            }
            for (int round = 0; round < 3; round++) {
                int[] order = round % 2 == 0 ? new int[]{38, 254} : new int[]{254, 38};
                for (int count : order) measureSections(biomes, allocations, count, round);
            }
        } finally {
            retainedSections = null;
            if (!wasEnabled) allocations.setThreadAllocatedMemoryEnabled(false);
        }
        assertEquals(before, inventory(folder), "Section measurement must leave the diagnostic snapshot untouched");
    }

    private static Registry<Biome> vanillaBiomes() {
        var source = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        source.listElements().forEach(holder -> registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        return registry.freeze();
    }

    private static void measureSections(Registry<Biome> biomes, ThreadMXBean allocations, int count, int round) {
        int repetitions = 128;
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = allocations.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        for (int i = 0; i < repetitions; i++) retainedSections = emptySections(biomes, count);
        long constructionNanos = System.nanoTime() - started;
        long constructionAllocated = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
        assertTrue(constructionAllocated > 0, "Construction allocation must be measured, not estimated");
        LevelChunkSection[] sections = (LevelChunkSection[]) retainedSections;
        for (LevelChunkSection section : sections) assertTrue(section.hasOnlyAir());
        int payloadBytes = serialize(sections);
        allocatedBefore = allocations.getThreadAllocatedBytes(thread);
        started = System.nanoTime();
        long totalBytes = 0;
        for (int i = 0; i < repetitions; i++) totalBytes += serialize(sections);
        serializedSink = totalBytes;
        long serializationNanos = System.nanoTime() - started;
        long serializationAllocated = allocations.getThreadAllocatedBytes(thread) - allocatedBefore;
        assertEquals((long) repetitions * payloadBytes, totalBytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "empty-section-cost");
        result.put("round", round);
        result.put("sections", count);
        result.put("repetitions", repetitions);
        result.put("sectionPayloadBytes", payloadBytes);
        result.put("constructionNanosTotal", constructionNanos);
        result.put("constructionAllocatedBytesTotal", constructionAllocated);
        result.put("constructionAllocatedBytesPerChunk", (double) constructionAllocated / repetitions);
        result.put("serializationNanosTotal", serializationNanos);
        result.put("serializationAllocatedBytesTotal", serializationAllocated);
        result.put("serializationAllocatedBytesPerChunk", (double) serializationAllocated / repetitions);
        result.put("allocationMethod", "com.sun.management.ThreadMXBean.getThreadAllocatedBytes, current thread");
        result.put("registryFixture", "Minecraft bootstrapped block states plus vanilla biome registry");
        result.put("limitation", "Empty sections only. Constructor allocation includes the section array; serialization includes a new exact-size heap buffer. Excludes full chunk/light/packet framing, compression and modded biome IDs. Not retained heap, FPS, MSPT or live game throughput.");
        emit(result);
    }

    private static LevelChunkSection[] emptySections(Registry<Biome> biomes, int count) {
        LevelChunkSection[] sections = new LevelChunkSection[count];
        for (int i = 0; i < count; i++) sections[i] = new LevelChunkSection(biomes);
        return sections;
    }

    private static int serialize(LevelChunkSection[] sections) {
        int expected = 0;
        for (LevelChunkSection section : sections) expected += section.getSerializedSize();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(expected, expected));
        try {
            for (LevelChunkSection section : sections) section.write(buffer);
            if (buffer.writerIndex() != expected) throw new AssertionError("Serialized section byte count differs from Minecraft's advertised size");
            return buffer.writerIndex();
        } finally {
            buffer.release();
        }
    }

    private record FileProof(long bytes, String sha256, String modified, String fileKey) {}
    private record Inventory(Map<String, FileProof> files, long bytes, Map<String, Long> storageRecords) {}

    private static Inventory inventory(Path folder) throws Exception {
        Map<String, FileProof> proofs = new LinkedHashMap<>();
        Map<String, Long> records = new LinkedHashMap<>();
        long bytes = 0;
        List<Path> paths;
        try (var walk = Files.walk(folder)) { paths = walk.sorted().limit(32769).toList(); }
        if (paths.size() > 32768) throw new IOException("Diagnostic snapshot exceeds file-count bound");
        for (Path path : paths) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) throw new IOException("Diagnostic snapshot contains a symlink");
            if (attributes.isDirectory()) continue;
            if (!attributes.isRegularFile()) throw new IOException("Diagnostic snapshot contains a non-regular file");
            bytes += attributes.size();
            if (bytes > 256L * 1024 * 1024) throw new IOException("Diagnostic snapshot exceeds the 256 MiB audit bound");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String relative = folder.relativize(path).toString().replace('\\', '/');
            proofs.put(relative, new FileProof(attributes.size(), HexFormat.of().formatHex(digest.digest()),
                attributes.lastModifiedTime().toString(), String.valueOf(attributes.fileKey())));
            String name = path.getFileName().toString();
            if (name.endsWith(".slvlr") || name.endsWith(".slvls") || name.endsWith(".mca")) {
                String kind = name.endsWith(".slvlr") ? "sableHoldingHeaderSlots"
                    : name.endsWith(".slvls") ? "sableStorageHeaderSlots" : folder.relativize(path).getName(0) + "AnvilHeaderSlots";
                long count = 0;
                if (attributes.size() > 0) {
                    if (attributes.size() < 4096) throw new IOException("Diagnostic storage header is truncated");
                    ByteBuffer header = ByteBuffer.allocate(4096);
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                        while (header.hasRemaining()) if (channel.read(header) <= 0) throw new IOException("Diagnostic header read failed");
                    }
                    header.flip();
                    while (header.hasRemaining()) if (header.getInt() != 0) count++;
                }
                records.merge(kind, count, Long::sum);
            } else if (name.endsWith(".slvl") || name.endsWith(".mcc")) {
                records.merge(name.endsWith(".slvl") ? "sableExternalFiles" : "anvilExternalFiles", 1L, Long::sum);
            }
        }
        return new Inventory(proofs, bytes, records);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void emit(Map<String, Object> result) { System.out.println("IPL_STORAGE_AUDIT " + JSON.toJson(result)); }
}
