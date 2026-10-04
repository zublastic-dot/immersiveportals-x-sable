package qouteall.imm_ptl.core.lighting;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

/** Bounded native-emission-only RGB propagation. No imported light is an input. */
public final class PortalColoredLightField {
    public static final int RADIUS = 14, MAX_VOXELS = 131_072, MAX_APERTURE = 1024;
    public record Rgb(int red, int green, int blue) {
        public static final Rgb DARK = new Rgb(0, 0, 0), WHITE = new Rgb(15, 15, 15);
        public Rgb { red = Math.clamp(red, 0, 15); green = Math.clamp(green, 0, 15); blue = Math.clamp(blue, 0, 15); }
        int packed() { return red | green << 4 | blue << 8; }
        static Rgb unpack(int value) { return new Rgb(value & 15, value >> 4 & 15, value >> 8 & 15); }
    }
    public record Cell(Rgb emission, Rgb filter, int opacity) {
        public static final Cell UNKNOWN = new Cell(Rgb.DARK, Rgb.DARK, 15);
        public static final Cell AIR = new Cell(Rgb.DARK, Rgb.WHITE, 0);
        public Cell { Objects.requireNonNull(emission); Objects.requireNonNull(filter); opacity = Math.clamp(opacity, 0, 15); }
    }
    @FunctionalInterface public interface Reader { Cell read(Pos position); }
    public record Average(float red, float green, float blue) {}
    public record Work(int reads, int steps, boolean complete) {}

    /** All arrays are private to an unfinished job; consumers see only an atomic final average. */
    public static final class Job {
        private final Reader reader;
        private final List<Pos> aperture;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ, count;
        private final int[] filter, light, queue;
        private final byte[] opacity;
        private final boolean[] queued;
        private int scanned, head, tail, pending, emitters, unknown;
        private int maxRed, maxGreen, maxBlue;
        private Average average;

        public Job(Reader reader, Collection<Pos> aperture) {
            this.reader = Objects.requireNonNull(reader);
            this.aperture = aperture.stream().distinct().toList();
            if (this.aperture.isEmpty() || this.aperture.size() > MAX_APERTURE)
                throw new IllegalArgumentException("unsupported aperture");
            long[] min = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
            long[] max = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
            for (Pos p : this.aperture) for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], p.component(axis)); max[axis] = Math.max(max[axis], p.component(axis));
            }
            for (int axis = 0; axis < 3; axis++) {
                min[axis] -= RADIUS; max[axis] += RADIUS;
                if (min[axis] < Integer.MIN_VALUE || max[axis] > Integer.MAX_VALUE || max[axis] - min[axis] + 1 > MAX_VOXELS)
                    throw new IllegalArgumentException("unsupported volume");
            }
            long volume = (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
            if (volume > MAX_VOXELS) throw new IllegalArgumentException("unsupported volume");
            minX = (int) min[0]; minY = (int) min[1]; minZ = (int) min[2];
            sizeX = (int) (max[0] - min[0] + 1); sizeY = (int) (max[1] - min[1] + 1); sizeZ = (int) (max[2] - min[2] + 1);
            count = (int) volume;
            filter = new int[count]; light = new int[count]; queue = new int[count]; opacity = new byte[count]; queued = new boolean[count];
        }

        public int volume() { return count; }
        public String captureStatus() {
            return "captured=" + scanned + "/" + count + ", unknown=" + unknown + ", emitters=" + emitters
                + ", sourceMax=" + maxRed + "/" + maxGreen + "/" + maxBlue + ", queued=" + pending;
        }
        public boolean complete() { return average != null; }
        public Average average() { if (average == null) throw new IllegalStateException("unfinished RGB snapshot"); return average; }
        public Rgb at(Pos position) {
            if (!complete()) throw new IllegalStateException("unfinished RGB snapshot");
            int x = position.x() - minX, y = position.y() - minY, z = position.z() - minZ;
            return x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ ? Rgb.DARK : Rgb.unpack(light[index(x, y, z)]);
        }
        public Work advance(int maxReads, int maxSteps) {
            return advance(maxReads, maxSteps, () -> true);
        }
        /** Cooperative deadline checked before each native read/flood step; a native call cannot be preempted. */
        public Work advance(int maxReads, int maxSteps, BooleanSupplier withinDeadline) {
            if (maxReads < 0 || maxSteps < 0) throw new IllegalArgumentException("negative budget");
            Objects.requireNonNull(withinDeadline);
            int reads = 0, steps = 0;
            while (scanned < count && reads < maxReads && withinDeadline.getAsBoolean()) {
                int i = scanned++, x = i % sizeX, y = i / sizeX % sizeY, z = i / (sizeX * sizeY);
                Cell cell = reader.read(new Pos(minX + x, minY + y, minZ + z));
                if (cell == null) cell = Cell.UNKNOWN;
                if (cell == Cell.UNKNOWN) unknown++;
                filter[i] = cell.filter.packed(); opacity[i] = (byte) cell.opacity; light[i] = cell.emission.packed();
                if (light[i] != 0) {
                    emitters++; maxRed = Math.max(maxRed, cell.emission.red());
                    maxGreen = Math.max(maxGreen, cell.emission.green()); maxBlue = Math.max(maxBlue, cell.emission.blue());
                    enqueue(i);
                }
                reads++;
            }
            // Never propagate through cells which have not been captured yet.
            if (scanned == count) while (pending > 0 && steps < maxSteps && withinDeadline.getAsBoolean()) {
                int i = queue[head]; head = (head + 1) % count; pending--; queued[i] = false; steps++;
                int x = i % sizeX, y = i / sizeX % sizeY, z = i / (sizeX * sizeY);
                if (x > 0) spread(i, i - 1); if (x + 1 < sizeX) spread(i, i + 1);
                if (y > 0) spread(i, i - sizeX); if (y + 1 < sizeY) spread(i, i + sizeX);
                if (z > 0) spread(i, i - sizeX * sizeY); if (z + 1 < sizeZ) spread(i, i + sizeX * sizeY);
            }
            if (scanned == count && pending == 0 && average == null) {
                long red = 0, green = 0, blue = 0;
                for (Pos p : aperture) {
                    int value = light[index(p.x() - minX, p.y() - minY, p.z() - minZ)];
                    red += value & 15; green += value >> 4 & 15; blue += value >> 8 & 15;
                }
                // Channel magnitude already contains native brightness. Do not weight it again.
                average = new Average((float) red / aperture.size(), (float) green / aperture.size(), (float) blue / aperture.size());
            }
            return new Work(reads, steps, complete());
        }
        private int index(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
        private void enqueue(int i) {
            if (queued[i]) return;
            queued[i] = true; queue[tail] = i; tail = (tail + 1) % count; pending++;
        }
        private void spread(int from, int to) {
            int attenuation = Math.max(1, opacity[to]), before = light[to], incoming = light[from], cap = filter[to], next = 0;
            for (int shift = 0; shift < 12; shift += 4) {
                int channel = Math.max(0, Math.min((incoming >> shift & 15) - attenuation, cap >> shift & 15));
                next |= Math.max(before >> shift & 15, channel) << shift;
            }
            if (next != before) { light[to] = next; enqueue(to); }
        }
    }
    private PortalColoredLightField() {}
}
