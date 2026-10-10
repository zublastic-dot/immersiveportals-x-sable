package ipl.sable.dim;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Compact immutable chunk-owned archive. The byte array never escapes this class. */
public final class IplDeferredAirSectionArchive {
    private static final int MAX_ENCODED_BYTES = 64 << 20;
    private static final long MAX_DECODED_BYTES = 128L << 20;
    private final byte[] encoded;

    private IplDeferredAirSectionArchive(byte[] owned) { encoded = owned; }

    public static @Nullable IplDeferredAirSectionArchive encode(@Nullable ListTag sections) {
        return encode(sections, MAX_ENCODED_BYTES, MAX_DECODED_BYTES);
    }

    static @Nullable IplDeferredAirSectionArchive encode(@Nullable ListTag sections, int encodedLimit, long decodedLimit) {
        if (sections == null || sections.isEmpty()) return null;
        CompoundTag root = new CompoundTag();
        root.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, sections);
        IplHostingChunkStorageMigration.validateDeferredAirSections(root);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new LimitedOutput(
            new GZIPOutputStream(new LimitedOutput(bytes, encodedLimit)), decodedLimit))) {
            NbtIo.write(root, output);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode bounded deferred air-section archive", failure);
        }
        return new IplDeferredAirSectionArchive(bytes.toByteArray());
    }

    /** Every decode returns independent mutable NBT; promotion can share this immutable object. */
    public ListTag decode() { return decode(MAX_DECODED_BYTES); }

    ListTag decode(long decodedLimit) {
        if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalStateException("Deferred air archive exceeds encoded limit");
        try (DataInputStream input = new DataInputStream(new LimitedInput(
            new GZIPInputStream(new ByteArrayInputStream(encoded)), decodedLimit))) {
            CompoundTag root = NbtIo.read(input, NbtAccounter.create(decodedLimit));
            if (input.read() != -1) throw new IOException("Trailing bytes in deferred air archive");
            IplHostingChunkStorageMigration.validateDeferredAirSections(root);
            return root.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot decode bounded deferred air-section archive", failure);
        }
    }

    public int encodedByteCount() { return encoded.length; }

    private static final class LimitedOutput extends FilterOutputStream {
        private long remaining;
        LimitedOutput(OutputStream output, long limit) { super(output); remaining = limit; }
        @Override public void write(int value) throws IOException { account(1); out.write(value); }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
            account(length); out.write(values, offset, length);
        }
        private void account(int count) throws IOException {
            if ((remaining -= count) < 0) throw new IOException("Deferred archive byte limit exceeded");
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        LimitedInput(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) account(1);
            return value;
        }
        @Override public int read(byte[] values, int offset, int length) throws IOException {
            int count = in.read(values, offset, (int) Math.min(length, remaining + 1));
            if (count > 0) account(count);
            return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = in.skip(Math.min(count, remaining + 1));
            if (skipped > 0) account((int) skipped);
            return skipped;
        }
        private void account(int count) throws IOException {
            if ((remaining -= count) < 0) throw new IOException("Deferred archive decoded byte limit exceeded");
        }
    }
}
