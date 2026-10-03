package qouteall.imm_ptl.core.sunlight;

import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/** Common, server-owned trajectory. No client classes, rendering state or world reads. */
public record SunlightProfile(boolean enabled, double pathRotationDegrees, Clock clock) {
    public static final int SCHEMA = 1;
    public enum Clock { SUN_ANGLE, WORLD_TIME }
    public record Sample(Vec3 solarDirection, Vec3 shadowDirection, double timeAngle, boolean solarActive) {}

    public SunlightProfile {
        Objects.requireNonNull(clock, "clock");
        if (!Double.isFinite(pathRotationDegrees) || Math.abs(pathRotationDegrees) > 180)
            throw new IllegalArgumentException("Sun path rotation must be finite and between -180 and 180 degrees");
    }
    public static SunlightProfile disabled() { return new SunlightProfile(false, 0, Clock.SUN_ANGLE); }
    public SunlightProfile withEnabled(boolean value) { return new SunlightProfile(value, pathRotationDegrees, clock); }

    /** Minecraft DimensionType's ordinary day clock, evaluated independently of client frame rate. */
    public static double vanillaSkyAngle(long dayTime, double partialTick) {
        if (!Double.isFinite(partialTick) || partialTick < 0 || partialTick > 1)
            throw new IllegalArgumentException("Invalid partial tick");
        double raw = fract((Math.floorMod(dayTime, 24000L) + partialTick) / 24000.0 - .25);
        return (raw * 2 + 1 - (Math.cos(raw * Math.PI) + 1) / 2) / 3;
    }
    public Sample sample(long dayTime) { return sample(dayTime, vanillaSkyAngle(dayTime, 0)); }
    public Sample sample(long dayTime, double skyAngle) {
        if (!Double.isFinite(skyAngle)) throw new IllegalArgumentException("Invalid sky angle");
        double sunAngle = fract(skyAngle + .25);
        double time = clock == Clock.WORLD_TIME ? Math.floorMod(dayTime, 24000L) / 24000.0 : packTimeAngle(sunAngle);
        double raw = fract(time - .25);
        double angle = (raw + (Math.cos(raw * Math.PI) * -.5 + .5 - raw) / 3) * Math.PI * 2;
        double rotation = Math.toRadians(pathRotationDegrees);
        Vec3 solar = new Vec3(-Math.sin(angle), Math.cos(angle) * Math.cos(rotation), -Math.cos(angle) * Math.sin(rotation));
        boolean moon = time > .5325 && time < .9675;
        return new Sample(solar, moon ? solar.scale(-1) : solar, time, !moon && solar.y > 1.0e-5);
    }
    private static double packTimeAngle(double sunAngle) {
        double a = fract(sunAngle - .033333333);
        double linear = a < .433333333 ? a * 1.15384615385 : a * .882352941176 + .117647058824;
        double half = linear > .5 ? 1 : 0, f = (linear * 2) % 1, smooth = f * f * (3 - 2 * f), mix = half < .5 ? .3 : -.1;
        return (f * (1 - mix) + smooth * mix + half) * .5;
    }
    private static double fract(double value) { return value - Math.floor(value); }
}
