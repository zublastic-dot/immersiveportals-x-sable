package qouteall.imm_ptl.core.teleportation;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Bounded, single-aperture geometric transport. No world or audio-thread access. */
public final class PortalSoundPath {
    private PortalSoundPath() {}

    /** Both bases are orthonormal; sourceNormal faces the source, exitNormal faces away. */
    public record Frame(Vec3 origin, Vec3 destination, Vec3 sourceU, Vec3 sourceV,
                        Vec3 destinationU, Vec3 destinationV, double halfWidth, double halfHeight) {
        public Vec3 sourceNormal() { return sourceU.cross(sourceV); }
        public Vec3 destinationNormal() { return destinationU.cross(destinationV); }
        public Vec3 entry(double u, double v) { return origin.add(sourceU.scale(u)).add(sourceV.scale(v)); }
        public Vec3 exit(double u, double v) { return destination.add(destinationU.scale(u)).add(destinationV.scale(v)); }
        public Vec3 transform(Vec3 source) {
            return destination.add(transformVector(source.subtract(origin)));
        }
        public Vec3 transformVector(Vec3 d) {
            return destinationU.scale(d.dot(sourceU))
                .add(destinationV.scale(d.dot(sourceV))).add(destinationNormal().scale(d.dot(sourceNormal())));
        }
        public Vec3 inverse(Vec3 point) { return origin.add(inverseVector(point.subtract(destination))); }
        public Vec3 inverseVector(Vec3 d) {
            return sourceU.scale(d.dot(destinationU)).add(sourceV.scale(d.dot(destinationV)))
                .add(sourceNormal().scale(d.dot(destinationNormal())));
        }
    }

    public record Path(Vec3 entry, Vec3 exit, Vec3 virtualSource, Vec3 presentation, double distance) {}

    @Nullable
    public static Path solve(Frame frame, Vec3 source, Vec3 listener) {
        if (!valid(frame) || !finite(source) || !finite(listener)) return null;
        Vec3 s = source.subtract(frame.origin);
        Vec3 l = listener.subtract(frame.destination);
        double sz = s.dot(frame.sourceNormal()), lz = l.dot(frame.destinationNormal());
        // A flipped/reverse portal owns the other face. Never transmit through a back face.
        if (sz < -1e-5 || lz > 1e-5) return null;
        double su = s.dot(frame.sourceU), sv = s.dot(frame.sourceV);
        double lu = l.dot(frame.destinationU), lv = l.dot(frame.destinationV);
        double t = sz - lz > 1e-8 ? sz / (sz - lz) : .5;
        double u = su + (lu - su) * t, v = sv + (lv - sv) * t;
        if (Math.abs(u) > frame.halfWidth || Math.abs(v) > frame.halfHeight) {
            // Convex sum of two distances: an outside unconstrained minimum has its
            // constrained minimum on one of the four edges. Fixed work, no recursion.
            double best = Double.POSITIVE_INFINITY, bestU = 0, bestV = 0;
            for (int edge = 0; edge < 4; edge++) {
                boolean vertical = edge < 2;
                double fixed = (edge % 2 == 0 ? -1 : 1) * (vertical ? frame.halfWidth : frame.halfHeight);
                double low = -(vertical ? frame.halfHeight : frame.halfWidth), high = -low;
                for (int step = 0; step < 32; step++) {
                    double a = low + (high - low) / 3, b = high - (high - low) / 3;
                    double da = length(frame, source, listener, vertical ? fixed : a, vertical ? a : fixed);
                    double db = length(frame, source, listener, vertical ? fixed : b, vertical ? b : fixed);
                    if (da <= db) high = b; else low = a;
                }
                double q = (low + high) * .5;
                double eu = vertical ? fixed : q, ev = vertical ? q : fixed;
                double distance = length(frame, source, listener, eu, ev);
                if (distance < best) { best = distance; bestU = eu; bestV = ev; }
            }
            u = bestU;
            v = bestV;
        }
        Vec3 entry = frame.entry(u, v), exit = frame.exit(u, v), virtual = frame.transform(source);
        double distance = source.distanceTo(entry) + listener.distanceTo(exit);
        Vec3 direction = exit.subtract(listener);
        if (direction.lengthSqr() < 1e-12) direction = virtual.subtract(listener);
        if (direction.lengthSqr() < 1e-12) direction = frame.destinationNormal();
        // OpenAL sees exactly the complete path length once, with the aperture's bearing.
        // Vec3.normalize deliberately returns ZERO below 1e-4, which would make
        // an emitter jump to the ear just before crossing the aperture. The
        // nonzero fallback above permits exact finite normalization here.
        return new Path(entry, exit, virtual, listener.add(direction.scale(distance / direction.length())), distance);
    }

    private static double length(Frame f, Vec3 source, Vec3 listener, double u, double v) {
        return source.distanceTo(f.entry(u, v)) + listener.distanceTo(f.exit(u, v));
    }

    static boolean valid(Frame f) {
        return f != null && finite(f.origin) && finite(f.destination)
            && unit(f.sourceU) && unit(f.sourceV) && unit(f.destinationU) && unit(f.destinationV)
            && Math.abs(f.sourceU.dot(f.sourceV)) < 1e-5
            && Math.abs(f.destinationU.dot(f.destinationV)) < 1e-5
            && Double.isFinite(f.halfWidth) && Double.isFinite(f.halfHeight)
            && f.halfWidth > 0 && f.halfWidth <= 128 && f.halfHeight > 0 && f.halfHeight <= 128;
    }

    public static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static boolean unit(Vec3 v) { return finite(v) && Math.abs(v.lengthSqr() - 1) < 1e-5; }
}
