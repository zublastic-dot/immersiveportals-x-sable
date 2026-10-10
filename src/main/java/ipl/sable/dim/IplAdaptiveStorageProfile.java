package ipl.sable.dim;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;

/** One session-wide storage envelope, with a persistent floor for subsequent starts. */
public final class IplAdaptiveStorageProfile {
    public static final String FILE_NAME = "ipl-storage-profile.json";
    public static final Bounds FULL_RANGE = new Bounds(-2032, 4064);
    private static final int VERSION = 1;
    private static final int MAX_MARKER_BYTES = 4096;

    private IplAdaptiveStorageProfile() {}

    public record Bounds(int minY, int height) {
        public Bounds {
            if (minY < -2032 || (minY & 15) != 0 || height < 16 || height > 4064
                || (height & 15) != 0 || (long) minY + height > 2032) {
                throw new IllegalArgumentException("Invalid hosting storage bounds: minY=" + minY + ", height=" + height);
            }
        }

        public int maxY() { return minY + height; }
        public int sectionCount() { return height >> 4; }

        public Bounds union(Bounds other) {
            int minimum = Math.min(minY, other.minY);
            return new Bounds(minimum, Math.max(maxY(), other.maxY()) - minimum);
        }

        public boolean contains(Bounds other) {
            return minY <= other.minY && maxY() >= other.maxY();
        }
    }

    public static Bounds select(Bounds parentDimensions, Optional<Bounds> savedData, Optional<Bounds> previous) {
        Bounds selected = savedData.map(parentDimensions::union).orElse(parentDimensions);
        return previous.map(selected::union).orElse(selected);
    }

    public static Optional<Bounds> read(Path hostingDimensionFolder) throws IOException {
        Path marker = hostingDimensionFolder.resolve(FILE_NAME);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > MAX_MARKER_BYTES) {
            throw new IOException("Invalid hosting storage profile file: " + marker);
        }
        try (JsonReader reader = new JsonReader(new StringReader(Files.readString(marker, StandardCharsets.UTF_8)))) {
            reader.setLenient(false);
            HashMap<String, Integer> fields = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (reader.peek() != JsonToken.NUMBER) throw new IOException("Non-integer profile field: " + name);
                String number = reader.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)") || fields.putIfAbsent(name, Integer.parseInt(number)) != null) {
                    throw new IOException("Invalid or duplicate profile field: " + name);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.keySet().equals(Set.of("version", "min_y", "height"))
                || fields.get("version") != VERSION) {
                throw new IOException("Unsupported or incomplete hosting storage profile: " + marker);
            }
            return Optional.of(new Bounds(fields.get("min_y"), fields.get("height")));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("Invalid hosting storage profile: " + marker, failure);
        }
    }

    /** Write before allocating storage; failure aborts startup instead of leaving an unrecorded profile. */
    public static void persist(Path hostingDimensionFolder, Bounds selected) throws IOException {
        Optional<Bounds> previous = read(hostingDimensionFolder);
        if (previous.isPresent()) {
            if (!selected.contains(previous.get())) throw new IOException("Refusing to shrink the recorded hosting profile");
            if (selected.equals(previous.get())) return;
        }
        Files.createDirectories(hostingDimensionFolder);
        Path temporary = Files.createTempFile(hostingDimensionFolder, "ipl-storage-profile-", ".tmp");
        try {
            byte[] bytes = ("{\n  \"version\": " + VERSION + ",\n  \"min_y\": " + selected.minY()
                + ",\n  \"height\": " + selected.height() + "\n}\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, hostingDimensionFolder.resolve(FILE_NAME),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
