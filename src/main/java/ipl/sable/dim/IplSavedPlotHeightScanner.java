package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Pre-level-allocation inventory of Sable 2.0.6 storage. Never instantiate Sable's
 * storage reader here: it opens CREATE/WRITE and pads/truncates files on close.
 * Reads are bounded and any unreadable/unknown record aborts profile selection.
 */
public final class IplSavedPlotHeightScanner {
    private static final int HEADER_BYTES = 4096;
    private static final int SLOTS = 1024;
    private static final Pattern FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)(?:\\.(\\d+))?\\.(slvlr|slvls)");
    private static final Pattern EXTERNAL = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)(?:\\.(\\d+))?\\.(r|s)");
    private static final Pattern RECORD = Pattern.compile("(\\d+)\\.slvl");
    private static final Limits DEFAULT_LIMITS = new Limits(32768, 1_048_576,
        64L << 20, 128L << 20, 8L << 30);

    private IplSavedPlotHeightScanner() {}

    public static Optional<IplAdaptiveStorageProfile.Bounds> scan(Path hostingDimensionFolder) throws IOException {
        return scan(hostingDimensionFolder, DEFAULT_LIMITS);
    }

    static Optional<IplAdaptiveStorageProfile.Bounds> scan(Path folder, Limits limits) throws IOException {
        return new Scan(limits).run(folder.toAbsolutePath().normalize());
    }

    record Limits(int files, int records, long encodedRecordBytes, long decodedRecordBytes, long totalBytes) {}
    private record Span(int slot, long start, long end) {}
    private record Address(String region, int storage, int slot) {}
    private record Source(String region, Integer storage, String stem) {
        boolean holding() { return storage == null; }
        Address address(int slot) { return new Address(region, storage, slot); }
    }

    private static final class Scan {
        private final Limits limits;
        private final Map<Path, BasicFileAttributes> observed = new HashMap<>();
        private final Set<Path> consumedExternal = new HashSet<>();
        private final Set<Address> available = new HashSet<>();
        private final Set<Address> required = new HashSet<>();
        private IplAdaptiveStorageProfile.Bounds bounds;
        private long bytes;
        private int records;

        Scan(Limits limits) { this.limits = limits; }

        Optional<IplAdaptiveStorageProfile.Bounds> run(Path folder) throws IOException {
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            directory(folder);
            IplSavedRegionHeightScanner.scan(folder).ifPresent(this::include);
            Path sublevels = folder.resolve("sublevels");
            if (Files.exists(sublevels, LinkOption.NOFOLLOW_LINKS)) {
                List<Path> entries = children(sublevels);
                for (Path file : entries) {
                    Matcher match = FILE.matcher(file.getFileName().toString());
                    if (match.matches()) {
                        Source source = source(match, file);
                        scanFile(file, source);
                    } else if (!EXTERNAL.matcher(file.getFileName().toString()).matches()) {
                        throw invalid(file, "unrecognized Sable storage entry");
                    }
                }
                // Retain orphaned external payload extents too, without changing their
                // reachability. Missing header stubs still cannot satisfy holding pointers.
                for (Path dir : entries) {
                    Matcher match = EXTERNAL.matcher(dir.getFileName().toString());
                    if (!match.matches()) continue;
                    Source source = source(match, dir);
                    for (Path file : children(dir)) {
                        Matcher record = RECORD.matcher(file.getFileName().toString());
                        if (!record.matches()) throw invalid(file, "unrecognized external record");
                        int slot = number(record.group(1), file);
                        if (slot >= SLOTS) throw invalid(file, "external record slot exceeds header capacity");
                        if (consumedExternal.add(file)) consume(readExternal(file), source, file);
                    }
                }
            }
            if (!available.containsAll(required)) {
                Set<Address> missing = new HashSet<>(required);
                missing.removeAll(available);
                throw invalid(folder, "holding pointers reference missing storage records: " + missing.iterator().next());
            }
            // The server's world lock excludes another normal server. Also detect
            // files or directory inventories changing during this preflight.
            for (var entry : observed.entrySet()) {
                BasicFileAttributes now = Files.readAttributes(entry.getKey(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes before = entry.getValue();
                if (now.size() != before.size() || !now.lastModifiedTime().equals(before.lastModifiedTime())
                    || !java.util.Objects.equals(now.fileKey(), before.fileKey())) {
                    throw invalid(entry.getKey(), "storage changed while being scanned");
                }
            }
            return Optional.ofNullable(bounds);
        }

        private Source source(Matcher match, Path path) throws IOException {
            int rx = number(match.group(1), path);
            int rz = number(match.group(2), path);
            Integer storage = match.group(3) == null ? null : number(match.group(3), path);
            if (storage != null && (storage < 0 || storage > Short.MAX_VALUE)) {
                throw invalid(path, "storage index outside Sable pointer range");
            }
            String extension = match.group(4);
            if ((extension.equals("slvlr") || extension.equals("r")) != (storage == null)) {
                throw invalid(path, "storage filename shape does not match extension");
            }
            String stem = "r." + rx + "." + rz + (storage == null ? "" : "." + storage);
            return new Source(rx + ":" + rz, storage, stem);
        }

        private void scanFile(Path path, Source source) throws IOException {
            BasicFileAttributes attr = regular(path);
            // Sable can create an unused zero-byte file before its first write.
            if (attr.size() == 0) return;
            if (attr.size() < HEADER_BYTES) throw invalid(path, "truncated span header");
            int sectorSize = source.holding() ? 128 : 4096;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer header = read(channel, 0, HEADER_BYTES, path);
                List<Span> spans = new ArrayList<>();
                for (int slot = 0; slot < SLOTS; slot++) {
                    int packed = header.getInt();
                    if (packed == 0) continue;
                    long start = ((packed >>> 8) & 0xffffffL) * sectorSize;
                    long end = start + (packed & 255L) * sectorSize;
                    if (start < HEADER_BYTES || end <= start || start + 5 > attr.size()) {
                        throw invalid(path, "invalid or truncated span at slot " + slot);
                    }
                    spans.add(new Span(slot, start, end));
                }
                spans.sort(Comparator.comparingLong(Span::start));
                long previousEnd = HEADER_BYTES;
                for (Span span : spans) {
                    if (span.start < previousEnd) throw invalid(path, "overlapping record spans");
                    previousEnd = span.end;
                    ByteBuffer prefix = read(channel, span.start, 5, path);
                    int length = prefix.getInt();
                    int flags = Byte.toUnsignedInt(prefix.get());
                    if (length < 1 || (long) length + 4 > span.end - span.start
                        || span.start + 4 + length > attr.size()) {
                        throw invalid(path, "invalid record length at slot " + span.slot);
                    }
                    byte[] payload;
                    if (flags == 0x10) {
                        if (length != 1) throw invalid(path, "external record also has internal payload");
                        Path externalDir = path.getParent().resolve(source.stem + (source.holding() ? ".r" : ".s"));
                        directory(externalDir);
                        Path external = externalDir.resolve(span.slot + ".slvl");
                        payload = readExternal(external);
                        consumedExternal.add(external);
                    } else if (flags == 0) {
                        payload = read(channel, span.start + 5, length - 1, path).array();
                    } else {
                        throw invalid(path, "unknown record flags " + flags);
                    }
                    consume(payload, source, path);
                    if (!source.holding()) available.add(source.address(span.slot));
                }
            }
        }

        private void consume(byte[] payload, Source source, Path path) throws IOException {
            if (++records > limits.records) throw invalid(path, "record scan limit exceeded");
            if (payload.length == 0) throw invalid(path, "empty NBT record");
            CompoundTag root;
            boolean gzip = payload.length >= 2 && (payload[0] & 255) == 0x1f && (payload[1] & 255) == 0x8b;
            InputStream encoded = new ByteArrayInputStream(payload);
            try (InputStream decoded = gzip ? new GZIPInputStream(encoded) : encoded;
                 DataInputStream input = new DataInputStream(new LimitedInput(decoded, limits.decodedRecordBytes, this, path))) {
                root = NbtIo.read(input, NbtAccounter.create(limits.decodedRecordBytes));
                if (input.read() != -1) throw invalid(path, "trailing bytes after NBT record");
            } catch (RuntimeException e) {
                throw new IOException("Refusing adaptive storage: malformed or oversized NBT in " + path, e);
            }
            if (source.holding()) {
                if (!root.contains("pointers", Tag.TAG_INT_ARRAY)) throw invalid(path, "holding record has no pointer array");
                for (int packed : root.getIntArray("pointers")) {
                    int storage = (short) (packed >> 16);
                    int slot = (short) packed;
                    if (storage < 0 || slot < 0 || slot >= SLOTS) throw invalid(path, "invalid holding pointer");
                    required.add(new Address(source.region, storage, slot));
                }
            } else {
                if (!root.contains("plot", Tag.TAG_COMPOUND)) throw invalid(path, "saved sublevel has no plot");
                IplPlotStorageMigration.occupiedBounds(root.getCompound("plot")).ifPresent(this::include);
            }
        }

        private byte[] readExternal(Path path) throws IOException {
            BasicFileAttributes attr = regular(path);
            if (attr.size() > limits.encodedRecordBytes || attr.size() > Integer.MAX_VALUE) {
                throw invalid(path, "external record exceeds encoded byte limit");
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                return read(channel, 0, (int) attr.size(), path).array();
            }
        }

        private ByteBuffer read(FileChannel channel, long offset, int length, Path path) throws IOException {
            if (length < 0 || length > limits.encodedRecordBytes) throw invalid(path, "encoded byte limit exceeded");
            bytes += length;
            if (bytes > limits.totalBytes) throw invalid(path, "total scan byte limit exceeded");
            ByteBuffer result = ByteBuffer.allocate(length);
            while (result.hasRemaining()) {
                int count = channel.read(result, offset + result.position());
                if (count < 0) throw invalid(path, "truncated record");
                if (count == 0) throw invalid(path, "record read made no progress");
            }
            return result.flip();
        }

        private List<Path> children(Path dir) throws IOException {
            directory(dir);
            try (var stream = Files.list(dir)) {
                List<Path> result = stream.limit((long) limits.files + 1).sorted().toList();
                if (result.size() > limits.files) throw invalid(dir, "directory entry limit exceeded");
                return result;
            }
        }

        private void directory(Path dir) throws IOException {
            if (!attributes(dir).isDirectory()) throw invalid(dir, "expected a directory without symlinks");
        }

        private BasicFileAttributes regular(Path path) throws IOException {
            BasicFileAttributes attr = attributes(path);
            if (!attr.isRegularFile()) throw invalid(path, "expected a regular file without symlinks");
            return attr;
        }

        private BasicFileAttributes attributes(Path path) throws IOException {
            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attr.isSymbolicLink()) throw invalid(path, "symbolic links are not supported in the save inventory");
            observed.putIfAbsent(path, attr);
            if (observed.size() > limits.files) throw invalid(path, "file scan limit exceeded");
            return attr;
        }

        private void include(IplAdaptiveStorageProfile.Bounds extent) {
            bounds = bounds == null ? extent : bounds.union(extent);
        }
    }

    private static int number(String value, Path path) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (!Integer.toString(parsed).equals(value)) throw invalid(path, "noncanonical numeric filename");
            return parsed;
        } catch (NumberFormatException e) {
            throw invalid(path, "numeric filename outside supported range");
        }
    }

    private static IOException invalid(Path path, String reason) {
        return new IOException("Refusing adaptive storage: " + reason + " (" + path + ")");
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        private final Scan scan;
        private final Path path;
        LimitedInput(InputStream input, long limit, Scan scan, Path path) {
            super(input);
            remaining = limit;
            this.scan = scan;
            this.path = path;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1) account(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, (int) Math.min(length, remaining + 1));
            if (count > 0) account(count);
            return count;
        }
        private void account(int count) throws IOException {
            if ((remaining -= count) < 0) throw invalid(path, "decoded NBT byte limit exceeded");
            scan.bytes += count;
            if (scan.bytes > scan.limits.totalBytes) throw invalid(path, "total scan byte limit exceeded");
        }
    }
}
