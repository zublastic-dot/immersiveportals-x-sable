package qouteall.imm_ptl.core.lighting;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/** Convex screen aperture geometry. Coordinates and edge distances are framebuffer pixels. */
public final class PortalBloomAperture {
    public static final int MAX_EDGES = 32;
    public static final float FOOTPRINT_MARGIN = 1.5f;
    public record Point(float x, float y) {}
    public record Mask(float[] edges, Point interior) {
        public int count() { return edges.length / 3; }
    }

    private PortalBloomAperture() {}

    /** Intersects every projected opening with the viewport, including nested portal openings. */
    public static Mask project(List<List<Vector4f>> openings, Matrix4fc viewProjection, int width, int height) {
        if (openings.isEmpty() || width <= 0 || height <= 0) return null;
        List<Point> intersection = List.of(new Point(0, 0), new Point(width, 0),
            new Point(width, height), new Point(0, height));
        for (var opening : openings) {
            if (opening.size() != 4) return null;
            List<Vector4f> clip = opening.stream().map(p -> viewProjection.transform(new Vector4f(p))).toList();
            for (int plane = 0; plane < 5; plane++) clip = clipHomogeneous(clip, plane);
            if (clip.size() < 3) return null;
            List<Point> projected = clip.stream().map(p -> new Point((p.x / p.w + 1) * width * .5f,
                (p.y / p.w + 1) * height * .5f)).toList();
            float[] edges = edges(projected);
            if (edges == null) return null;
            for (int i = 0; i < edges.length; i += 3)
                intersection = clip(intersection, edges[i], edges[i + 1], edges[i + 2]);
            if (intersection.size() < 3 || intersection.size() > MAX_EDGES) return null;
        }
        float[] edges = edges(intersection);
        if (edges == null) return null;
        // A guaranteed safe level-zero sample is also needed when a coarse bloom
        // kernel misses a narrow opening entirely. Otherwise upsampling creates a dark rim.
        var inset = intersection;
        for (int i = 0; i < edges.length; i += 3)
            inset = clip(inset, edges[i], edges[i + 1], edges[i + 2] - FOOTPRINT_MARGIN);
        if (inset.size() < 3) return null;
        float x = 0, y = 0;
        for (var p : inset) { x += p.x; y += p.y; }
        return new Mask(edges, new Point(x / inset.size(), y / inset.size()));
    }

    private static float distance(Vector4f p, int plane) {
        return switch (plane) {
            case 0 -> p.w - .00001f;
            case 1 -> p.w + p.x;
            case 2 -> p.w - p.x;
            case 3 -> p.w + p.y;
            default -> p.w - p.y;
        };
    }

    private static List<Vector4f> clipHomogeneous(List<Vector4f> input, int plane) {
        var result = new ArrayList<Vector4f>();
        if (input.isEmpty()) return result;
        var previous = input.getLast(); float before = distance(previous, plane);
        for (var next : input) {
            float after = distance(next, plane);
            if ((before >= 0) != (after >= 0))
                result.add(new Vector4f(previous).lerp(next, before / (before - after)));
            if (after >= 0) result.add(next);
            previous = next; before = after;
        }
        return result;
    }

    private static float[] edges(List<Point> polygon) {
        if (polygon.size() < 3 || polygon.size() > MAX_EDGES) return null;
        float area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            area += a.x * b.y - b.x * a.y;
        }
        if (!Float.isFinite(area) || Math.abs(area) < .01f) return null;
        float sign = Math.signum(area);
        var result = new ArrayList<Float>();
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
            float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
            if (length < .001f) continue;
            float nx = -dy * sign / length, ny = dx * sign / length;
            result.add(nx); result.add(ny); result.add(-nx * a.x - ny * a.y);
        }
        if (result.size() < 9) return null;
        float[] packed = new float[result.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = result.get(i);
        return packed;
    }

    private static List<Point> clip(List<Point> input, float x, float y, float c) {
        var result = new ArrayList<Point>();
        if (input.isEmpty()) return result;
        var previous = input.getLast(); float before = previous.x * x + previous.y * y + c;
        for (var next : input) {
            float after = next.x * x + next.y * y + c;
            if ((before >= 0) != (after >= 0)) {
                float t = before / (before - after);
                result.add(new Point(previous.x + (next.x - previous.x) * t, previous.y + (next.y - previous.y) * t));
            }
            if (after >= 0) result.add(next);
            previous = next; before = after;
        }
        return result;
    }
}
