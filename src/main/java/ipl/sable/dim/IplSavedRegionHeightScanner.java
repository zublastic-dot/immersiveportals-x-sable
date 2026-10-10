package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only occupied-Y inventory for Minecraft 1.21.1 Anvil terrain, entities and POIs. */
public final class IplSavedRegionHeightScanner {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-storage");
    private static final int DATA_VERSION = 3955;
    private static final int HEADER = 8192;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Pattern EXTERNAL = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final Limits DEFAULT_LIMITS = new Limits(32768, 1_048_576, 64L << 20, 128L << 20, 8L << 30);
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final Set<String> STATUSES = Set.of("minecraft:empty", "minecraft:structure_starts",
        "minecraft:structure_references", "minecraft:biomes", "minecraft:noise", "minecraft:surface",
        "minecraft:carvers", "minecraft:features", "minecraft:initialize_light", "minecraft:light",
        "minecraft:spawn", "minecraft:full");

    private IplSavedRegionHeightScanner() {}

    public static Optional<IplAdaptiveStorageProfile.Bounds> scan(Path hostingDimensionFolder) throws IOException {
        return scan(hostingDimensionFolder, DEFAULT_LIMITS);
    }

    /** Shared nonmutating terrain preflight for the live ChunkSerializer load boundary. */
    public static Optional<IplAdaptiveStorageProfile.Bounds> chunkBounds(CompoundTag chunk) throws IOException {
        Scan scan = new Scan(DEFAULT_LIMITS);
        scan.inspect(chunk, "region", Path.of("in-memory-hosting-chunk"));
        return Optional.ofNullable(scan.bounds);
    }

    static Optional<IplAdaptiveStorageProfile.Bounds> scan(Path folder, Limits limits) throws IOException {
        Report report = scanReport(folder, limits);
        LOG.info("[IPL-SABLE-STORAGE] Anvil inventory: counts={} occupiedBounds={} contributions={}",
            report.counts(), report.bounds().orElse(null), report.contributions());
        return report.bounds();
    }

    /** Fixed-category aggregate evidence; never records individual chunk coordinates. */
    public record Report(Optional<IplAdaptiveStorageProfile.Bounds> bounds, Map<String, Long> counts,
                         Map<String, IplAdaptiveStorageProfile.Bounds> contributions) {
        public Report {
            counts = Map.copyOf(counts);
            contributions = Map.copyOf(contributions);
        }
    }

    public static Report scanReport(Path folder) throws IOException { return scanReport(folder, DEFAULT_LIMITS); }

    static Report scanReport(Path folder, Limits limits) throws IOException {
        return new Scan(limits).run(folder.toAbsolutePath().normalize());
    }

    /** Opt-in audit of a copied save. Encoding/hashing is never part of normal startup selection. */
    public static ArchiveReport auditDeferredArchives(Path folder, int minY, int height) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive audit requires a copied dimension directory");
        ArchiveAudit audit = new ArchiveAudit(new IplAdaptiveStorageProfile.Bounds(minY, height));
        Report inventory = new Scan(DEFAULT_LIMITS, audit).run(folder.toAbsolutePath().normalize());
        if (inventory.bounds().isPresent() && !audit.candidate.contains(inventory.bounds().get())) {
            throw new IOException("Archive audit candidate cannot contain the Anvil occupied/fallback bounds");
        }
        return audit.report();
    }

    public record ArchiveReport(IplAdaptiveStorageProfile.Bounds candidate, long terrainRecords,
                                long archiveChunks, long archivedSections, long totalCompressedBytes,
                                long maxCompressedBytes, long estimatedEncodedArrayShallowBytes,
                                String footprintAssumption, String digestSchema,
                                Map<String, String> projectedArchiveSha256ByChunk,
                                Map<String, String> persistedArchiveSha256ByChunk) {
        public ArchiveReport {
            projectedArchiveSha256ByChunk = Map.copyOf(projectedArchiveSha256ByChunk);
            persistedArchiveSha256ByChunk = Map.copyOf(persistedArchiveSha256ByChunk);
        }
    }

    private static final class ArchiveAudit {
        private static final int MAX_TERRAIN_RECORDS = 65536;
        final IplAdaptiveStorageProfile.Bounds candidate;
        final Map<String, String> projected = new TreeMap<>();
        final Map<String, String> persisted = new TreeMap<>();
        long records, chunks, sections, bytes, maxBytes, arrayFootprint;

        ArchiveAudit(IplAdaptiveStorageProfile.Bounds candidate) { this.candidate = candidate; }

        void inspect(CompoundTag root, String recordKey) throws IOException {
            if (++records > MAX_TERRAIN_RECORDS) throw new IOException("Archive audit terrain-record limit exceeded");
            if (!root.contains("DataVersion", Tag.TAG_INT) || root.getInt("DataVersion") != DATA_VERSION) {
                throw new IOException("Archive audit requires Minecraft 1.21.1 terrain records");
            }
            CompoundTag prepared = IplHostingChunkStorageMigration.prepareForLoad(root, candidate.minY(), candidate.height());
            ListTag deferred = prepared.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND);
            IplDeferredAirSectionArchive archive = IplDeferredAirSectionArchive.encode(deferred);
            if (archive != null) {
                int encoded = archive.encodedByteCount();
                chunks++;
                sections += deferred.size();
                bytes += encoded;
                maxBytes = Math.max(maxBytes, encoded);
                arrayFootprint += (16L + encoded + 7) & ~7L;
                projected.put(recordKey, archiveDigest(deferred));
            }
            ListTag existing = root.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND);
            if (!existing.isEmpty()) persisted.put(recordKey, archiveDigest(existing));
        }

        ArchiveReport report() {
            return new ArchiveReport(candidate, records, chunks, sections, bytes, maxBytes, arrayFootprint,
                "Estimate only: one encoded byte[] per archive, 16-byte array header and 8-byte alignment. "
                    + "Excludes archive objects, references, temporary encoding/decoding allocation and all other chunk memory.",
                "ipl-deferred-air-v1: sections sorted by absolute Y; compound keys sorted; exact NBT tag types/values; list order retained",
                projected, persisted);
        }
    }

    static String archiveDigest(ListTag sections) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                output.writeUTF("ipl-deferred-air-v1");
                List<CompoundTag> ordered = new ArrayList<>();
                for (Tag section : sections) ordered.add((CompoundTag) section);
                ordered.sort(Comparator.comparingInt(section -> section.getInt("Y")));
                output.writeInt(ordered.size());
                for (CompoundTag section : ordered) writeCanonical(section, output);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeCanonical(Tag tag, DataOutputStream output) throws IOException {
        output.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            List<String> keys = compound.getAllKeys().stream().sorted().toList();
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                writeCanonical(compound.get(key), output);
            }
        } else if (tag instanceof ListTag list) {
            output.writeByte(list.getElementType());
            output.writeInt(list.size());
            for (Tag child : list) writeCanonical(child, output);
        } else tag.write(output);
    }

    record Limits(int files, int records, long encodedRecordBytes, long decodedRecordBytes, long totalBytes) {}
    private record Span(int slot, long start, long end) {}

    private static final class Scan {
        final Limits limits;
        final Map<Path, BasicFileAttributes> observed = new HashMap<>();
        final Set<Path> externalRead = new HashSet<>();
        final Map<String, Long> counts = new LinkedHashMap<>();
        final Map<String, IplAdaptiveStorageProfile.Bounds> contributions = new LinkedHashMap<>();
        final ArchiveAudit archiveAudit;
        IplAdaptiveStorageProfile.Bounds bounds;
        long bytes;
        int records;

        Scan(Limits limits) { this(limits, null); }
        Scan(Limits limits, ArchiveAudit archiveAudit) { this.limits = limits; this.archiveAudit = archiveAudit; }

        Report run(Path folder) throws IOException {
            for (String kind : List.of("region", "entities", "poi")) {
                Path dir = folder.resolve(kind);
                if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                List<Path> entries = children(dir);
                for (Path file : entries) {
                    Matcher match = REGION.matcher(file.getFileName().toString());
                    if (match.matches()) scanRegion(file, kind, number(match.group(1), file), number(match.group(2), file));
                    else if (!EXTERNAL.matcher(file.getFileName().toString()).matches()) {
                        throw invalid(file, "unrecognized Anvil storage entry");
                    }
                }
                for (Path file : entries) {
                    if (EXTERNAL.matcher(file.getFileName().toString()).matches() && !externalRead.contains(file)) {
                        regular(file);
                        // The orphan has no framing record that identifies its codec.
                        // Preserve the old envelope without guessing how to decode it.
                        include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.orphan_external");
                    }
                }
            }
            for (var entry : observed.entrySet()) {
                BasicFileAttributes before = entry.getValue();
                BasicFileAttributes after = Files.readAttributes(entry.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                    throw invalid(entry.getKey(), "Anvil storage changed while being scanned");
                }
            }
            counts.put("files", (long) observed.size());
            counts.put("encoded_and_decoded_bytes", bytes);
            return new Report(Optional.ofNullable(bounds), counts, contributions);
        }

        private void scanRegion(Path file, String kind, int rx, int rz) throws IOException {
            long size = regular(file).size();
            if (size == 0) return; // unused RegionFile, before its first write
            if (size < HEADER) throw invalid(file, "truncated Anvil header");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer header = read(channel, 0, HEADER, file);
                List<Span> spans = new ArrayList<>();
                for (int slot = 0; slot < 1024; slot++) {
                    int packed = header.getInt();
                    if (packed == 0) continue;
                    long start = ((packed >>> 8) & 0xffffffL) * 4096;
                    long end = start + (packed & 255L) * 4096;
                    if (start < HEADER || end <= start || start + 5 > size) throw invalid(file, "invalid Anvil span");
                    spans.add(new Span(slot, start, end));
                }
                spans.sort(Comparator.comparingLong(Span::start));
                long previousEnd = HEADER;
                for (Span span : spans) {
                    if (span.start < previousEnd) throw invalid(file, "overlapping Anvil spans");
                    previousEnd = span.end;
                    ByteBuffer prefix = read(channel, span.start, 5, file);
                    int length = prefix.getInt();
                    int flag = Byte.toUnsignedInt(prefix.get());
                    int codec = flag & 127;
                    if (length < 1 || (long) length + 4 > span.end - span.start || span.start + 4 + length > size) {
                        throw invalid(file, "invalid or truncated Anvil record length");
                    }
                    if (codec < 1 || codec > 4) throw invalid(file, "unsupported Anvil compression " + codec);
                    byte[] payload;
                    if ((flag & 128) != 0) {
                        if (length != 1) throw invalid(file, "external Anvil record has internal bytes");
                        long cx = (long) rx * 32 + (span.slot & 31);
                        long cz = (long) rz * 32 + (span.slot >> 5);
                        if (cx < Integer.MIN_VALUE || cx > Integer.MAX_VALUE || cz < Integer.MIN_VALUE || cz > Integer.MAX_VALUE) {
                            throw invalid(file, "chunk coordinate overflow");
                        }
                        Path external = file.getParent().resolve("c." + cx + "." + cz + ".mcc");
                        long externalBytes = regular(external).size();
                        if (externalBytes > limits.encodedRecordBytes || externalBytes > Integer.MAX_VALUE) {
                            throw invalid(external, "external Anvil record exceeds byte limit");
                        }
                        try (FileChannel externalChannel = FileChannel.open(external, StandardOpenOption.READ)) {
                            payload = read(externalChannel, 0, (int) externalBytes, external).array();
                        }
                        externalRead.add(external);
                    } else payload = read(channel, span.start + 5, length - 1, file).array();
                    CompoundTag root = decode(payload, codec, file);
                    inspect(root, kind, file);
                    if (archiveAudit != null && kind.equals("region")) {
                        archiveAudit.inspect(root, file.getFileName() + "#" + span.slot);
                    }
                }
            }
        }

        private CompoundTag decode(byte[] payload, int codec, Path path) throws IOException {
            if (++records > limits.records) throw invalid(path, "Anvil record count limit exceeded");
            try (InputStream decoded = RegionFileVersion.fromId(codec).wrap(new ByteArrayInputStream(payload));
                 DataInputStream input = new DataInputStream(new LimitedInput(decoded, this, path))) {
                CompoundTag result = NbtIo.read(input, NbtAccounter.create(limits.decodedRecordBytes));
                if (input.read() != -1) throw invalid(path, "trailing bytes after Anvil NBT");
                return result;
            } catch (RuntimeException e) {
                throw new IOException("Refusing adaptive storage: invalid or oversized Anvil NBT in " + path, e);
            }
        }

        private void inspect(CompoundTag root, String kind, Path path) throws IOException {
            count("records." + kind);
            if (!root.contains("DataVersion", Tag.TAG_INT) || root.getInt("DataVersion") != DATA_VERSION) {
                include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.data_version");
                return; // data-fixer formats are not guessed by a startup height scan
            }
            try {
                switch (kind) {
                    case "region" -> terrain(root, path);
                    case "entities" -> entities(list(root, "Entities", path, true), path, 0);
                    case "poi" -> poi(root, path);
                    default -> throw invalid(path, "unknown Anvil payload kind");
                }
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw new IOException("Refusing adaptive storage: invalid " + kind + " payload in " + path, failure);
            }
        }

        private void terrain(CompoundTag root, Path path) throws IOException {
            IplHostingChunkStorageMigration.validateSectionInventory(root);
            int deferred = root.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND).size();
            if (deferred > 0) counts.merge("deferred_air_sections", (long) deferred, Long::sum);
            String status = root.getString("Status");
            count(STATUSES.contains(status) ? "status." + status.substring(10) : "status.unknown");
            if (!STATUSES.contains(status)) {
                include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.unknown_status");
                return;
            }
            if (root.contains("below_zero_retrogen")) {
                include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.retrogen");
                return;
            }
            if (!status.equals("minecraft:full") && (root.contains("blending_data") || hasStructureState(root))) {
                include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.proto_generation_state");
                return; // Deferred structure/blending generation has not been height-remapped.
            }
            // Vanilla proto payload differs only by pending entities and carving masks;
            // the shared migration validates and rebases the latter plus indexed work.
            IplHostingChunkStorageMigration.indexedBounds(root).ifPresent(value -> include(value, "indexed_metadata"));
            for (Tag entry : list(root, "sections", path, true)) {
                CompoundTag section = compound(entry, path);
                if (!(section.get("Y") instanceof NumericTag number)) throw invalid(path, "section has no numeric Y");
                double encodedY = number.getAsDouble();
                if (!Double.isFinite(encodedY) || encodedY != Math.rint(encodedY) || encodedY < -128 || encodedY > 127) {
                    throw invalid(path, "section Y is not an exact byte-range integer");
                }
                int sectionY = (int) encodedY;
                boolean customBiome = nonDefaultBiomes(section, path);
                boolean occupied = false;
                if (section.contains("block_states")) {
                    if (!section.contains("block_states", Tag.TAG_COMPOUND)) throw invalid(path, "malformed block states");
                    ListTag palette = list(section.getCompound("block_states"), "palette", path, true);
                    if (palette.isEmpty()) throw invalid(path, "empty block-state palette");
                    for (Tag state : palette) {
                        CompoundTag block = compound(state, path);
                        if (!block.contains("Name", Tag.TAG_STRING)) throw invalid(path, "palette state has no name");
                        if (!AIR.contains(block.getString("Name"))) occupied = true;
                    }
                }
                if (occupied) section(sectionY, path, "block_sections");
                else count("air_sections");
                // The load/save migration retains excluded all-air section compounds,
                // including ambiguous plains padding and intentional custom biome edits.
                // They need durable NBT retention, not block/light array allocation.
                if (customBiome) count(occupied ? "custom_biome_block_sections" : "retained_air_biome_sections");
            }
            positioned(root, "block_entities", path);
            positioned(root, "block_ticks", path);
            positioned(root, "fluid_ticks", path);
            entities(list(root, "entities", path, false), path, 0);
            for (Tag entry : list(root, "neoforge:aux_lights", path, false)) {
                CompoundTag light = compound(entry, path);
                if (!light.contains("pos", Tag.TAG_LONG)) throw invalid(path, "auxiliary light has no packed position");
                blockY((int) (light.getLong("pos") << 52 >> 52), path, "auxiliary_lights");
            }
        }

        private boolean hasStructureState(CompoundTag root) {
            if (!root.contains("structures")) return false;
            if (!(root.get("structures") instanceof CompoundTag structures)) return true;
            for (String key : structures.getAllKeys()) {
                if (!(structures.get(key) instanceof CompoundTag entries) || !entries.isEmpty()) return true;
            }
            return false;
        }

        private boolean nonDefaultBiomes(CompoundTag section, Path path) throws IOException {
            if (!section.contains("biomes")) return false;
            if (!section.contains("biomes", Tag.TAG_COMPOUND)) throw invalid(path, "malformed biome container");
            if (!(section.getCompound("biomes").get("palette") instanceof ListTag palette)
                || palette.isEmpty() || palette.getElementType() != Tag.TAG_STRING) {
                throw invalid(path, "malformed biome palette");
            }
            // The bundled hosting generator's one biome is the_void. Preserve all
            // other palettes, including edits in air, instead of treating them as allocation.
            for (int i = 0; i < palette.size(); i++) {
                if (!palette.getString(i).equals("minecraft:the_void")) return true;
            }
            return false;
        }

        private void positioned(CompoundTag root, String key, Path path) throws IOException {
            for (Tag entry : list(root, key, path, false)) {
                CompoundTag position = compound(entry, path);
                if (!position.contains("y", Tag.TAG_INT)) throw invalid(path, key + " has no integer Y");
                blockY(position.getInt("y"), path, key);
            }
        }

        private void entities(ListTag entities, Path path, int depth) throws IOException {
            if (depth > 64) throw invalid(path, "passenger nesting exceeds scan limit");
            for (Tag entry : entities) {
                CompoundTag entity = compound(entry, path);
                if (!(entity.get("Pos") instanceof ListTag position) || position.size() != 3
                    || position.getElementType() != Tag.TAG_DOUBLE) throw invalid(path, "entity has malformed Pos");
                double y = position.getDouble(1);
                if (!Double.isFinite(y)) throw invalid(path, "entity has non-finite Y");
                // Vanilla permits entities outside block build height. A scan cannot
                // make them representable by inventing an illegal DimensionType.
                if (y < -2032 || y >= 2032) include(IplAdaptiveStorageProfile.FULL_RANGE, "fallback.entity_outside_native");
                else blockY((int) Math.floor(y), path, "entity_positions");
                entities(list(entity, "Passengers", path, false), path, depth + 1);
            }
        }

        private void poi(CompoundTag root, Path path) throws IOException {
            if (!root.contains("Sections", Tag.TAG_COMPOUND)) throw invalid(path, "POI record has no Sections compound");
            CompoundTag sections = root.getCompound("Sections");
            for (String key : sections.getAllKeys()) {
                int sectionY = number(key, path);
                CompoundTag section = compound(sections.get(key), path);
                for (Tag entry : list(section, "Records", path, true)) {
                    CompoundTag record = compound(entry, path);
                    if (!record.contains("pos", Tag.TAG_INT_ARRAY)) throw invalid(path, "POI has no integer-array position");
                    int[] pos = record.getIntArray("pos");
                    if (pos.length != 3 || (pos[1] >> 4) != sectionY) throw invalid(path, "POI position does not match its section");
                    blockY(pos[1], path, "poi_positions");
                }
            }
        }

        private void blockY(int y, Path path, String reason) throws IOException { section(Math.floorDiv(y, 16), path, reason); }
        private void section(int y, Path path, String reason) throws IOException {
            if (y < -127 || y > 126) throw invalid(path, "occupied section outside native storage envelope: " + y);
            include(new IplAdaptiveStorageProfile.Bounds(y * 16, 16), reason);
        }
        private void include(IplAdaptiveStorageProfile.Bounds next, String reason) {
            bounds = bounds == null ? next : bounds.union(next);
            count(reason);
            contributions.merge(reason, next, IplAdaptiveStorageProfile.Bounds::union);
        }
        private void count(String reason) { counts.merge(reason, 1L, Long::sum); }

        private ByteBuffer read(FileChannel channel, long offset, int length, Path path) throws IOException {
            if (length < 0 || length > limits.encodedRecordBytes) throw invalid(path, "encoded Anvil byte limit exceeded");
            account(length, path);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset + buffer.position());
                if (read <= 0) throw invalid(path, "truncated Anvil read");
            }
            return buffer.flip();
        }
        private void account(int count, Path path) throws IOException {
            bytes += count;
            if (bytes > limits.totalBytes) throw invalid(path, "total Anvil scan byte limit exceeded");
        }
        private List<Path> children(Path dir) throws IOException {
            if (!attributes(dir).isDirectory()) throw invalid(dir, "expected Anvil directory without symlinks");
            try (var stream = Files.list(dir)) {
                List<Path> result = stream.limit((long) limits.files + 1).sorted().toList();
                if (result.size() > limits.files) throw invalid(dir, "Anvil directory entry limit exceeded");
                return result;
            }
        }
        private BasicFileAttributes regular(Path path) throws IOException {
            BasicFileAttributes attr = attributes(path);
            if (!attr.isRegularFile()) throw invalid(path, "expected regular Anvil file without symlinks");
            return attr;
        }
        private BasicFileAttributes attributes(Path path) throws IOException {
            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attr.isSymbolicLink()) throw invalid(path, "Anvil symlink is not supported");
            observed.putIfAbsent(path, attr);
            if (observed.size() > limits.files) throw invalid(path, "Anvil file scan limit exceeded");
            return attr;
        }
    }

    private static ListTag list(CompoundTag root, String key, Path path, boolean required) throws IOException {
        if (!root.contains(key) && !required) return new ListTag();
        if (!(root.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid(path, "malformed or missing " + key + " list");
        }
        return list;
    }
    private static CompoundTag compound(Tag tag, Path path) throws IOException {
        if (!(tag instanceof CompoundTag compound)) throw invalid(path, "expected NBT compound");
        return compound;
    }
    private static int number(String value, Path path) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (!Integer.toString(parsed).equals(value)) throw invalid(path, "noncanonical numeric key");
            return parsed;
        } catch (NumberFormatException error) { throw invalid(path, "numeric key outside supported range"); }
    }
    private static IOException invalid(Path path, String reason) {
        return new IOException("Refusing adaptive storage: " + reason + " (" + path + ")");
    }
    private static final class LimitedInput extends FilterInputStream {
        final Scan scan;
        final Path path;
        long remaining;
        LimitedInput(InputStream input, Scan scan, Path path) {
            super(input); this.scan = scan; this.path = path; remaining = scan.limits.decodedRecordBytes;
        }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) account(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, (int) Math.min(length, remaining + 1));
            if (count > 0) account(count);
            return count;
        }
        private void account(int count) throws IOException {
            if ((remaining -= count) < 0) throw invalid(path, "decoded Anvil NBT byte limit exceeded");
            scan.account(count, path);
        }
    }
}
