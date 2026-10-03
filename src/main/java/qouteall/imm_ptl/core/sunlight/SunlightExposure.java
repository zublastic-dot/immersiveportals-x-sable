package qouteall.imm_ptl.core.sunlight;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.lighting.PortalSunGeometry;
import java.util.function.UnaryOperator;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Bounded CPU direct-solar visibility, shared by ordinary and one-hop portal queries. */
public final class SunlightExposure {
    public static final int MAX_READS = 4096;
    public static final double MAX_PORTAL_DISTANCE = 32;
    public record Sky(World cells, int ceiling, SunlightProfile.Sample sun, double strength) {}
    public record Aperture(Vec3 center, Vec3 normal, Vec3 u, Vec3 v, double width, double height,
                           UnaryOperator<Vec3> toSourcePoint, UnaryOperator<Vec3> fromSourceDirection, Sky source) {}
    public static final class Budget {
        private final int limit;
        private int reads;
        private boolean unknown;
        public Budget(int limit) {
            if (limit < 0 || limit > MAX_READS) throw new IllegalArgumentException("Invalid sunlight read budget");
            this.limit = limit;
        }
        public int reads() { return reads; }
        public boolean unknown() { return unknown; }
        public boolean exhausted() { return reads >= limit; }
        World bounded(World reader) {
            return pos -> {
                if (reads >= limit) { unknown = true; return Cell.UNKNOWN; }
                reads++;
                Cell value = reader.cell(pos);
                if (value == null || value == Cell.UNKNOWN) { unknown = true; return Cell.UNKNOWN; }
                return value;
            };
        }
    }
    private SunlightExposure() {}
    public static double weatherStrength(float rain, float thunder) {
        if (!Float.isFinite(rain) || !Float.isFinite(thunder)) return 0;
        return (1 - Math.clamp(rain, 0, 1)) * (1 - Math.clamp(thunder, 0, 1));
    }
    public static double direct(Sky sky, Vec3 eye, Budget budget) {
        if (!active(sky) || budget.exhausted()) return 0;
        return PortalSunGeometry.clearToSky(budget.bounded(sky.cells), eye, sky.sun.solarDirection(), sky.ceiling, MAX_READS)
            ? sky.strength : 0;
    }
    public static double through(World receiving, Vec3 eye, Aperture a, Budget budget) {
        if (!active(a.source) || budget.exhausted()) return 0;
        Vec3 direction = a.fromSourceDirection.apply(a.source.sun.solarDirection()).normalize();
        var hit = PortalSunGeometry.hit(eye, direction, a.normal, a.center, a.u, a.v, a.width / 2, a.height / 2);
        if (hit == null || hit.distance() > MAX_PORTAL_DISTANCE) return 0;
        if (!PortalSunGeometry.clearSegment(budget.bounded(receiving), eye, hit.position(), MAX_READS)) return 0;
        Vec3 exit = a.toSourcePoint.apply(hit.position()).add(a.source.sun.solarDirection().scale(.002));
        return direct(a.source, exit, budget);
    }
    private static boolean active(Sky sky) {
        return sky != null && sky.cells != null && sky.sun != null && sky.sun.solarActive()
            && Double.isFinite(sky.strength) && sky.strength > 0 && sky.strength <= 1;
    }
    /** Vanilla probability and environmental guards, with direct-sun intensity replacing ambient brightness. */
    public static boolean burnTick(double sunlight, float randomDraw, boolean wet, boolean powderSnow, boolean wasPowderSnow) {
        return !wet && !powderSnow && !wasPowderSnow && Double.isFinite(sunlight) && sunlight > .5 && sunlight <= 1
            && Float.isFinite(randomDraw) && randomDraw >= 0 && randomDraw < 1
            && randomDraw * 30 < (sunlight - .4) * 2;
    }
}
