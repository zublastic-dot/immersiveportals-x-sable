package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.lwjgl.opengl.GL20;
import java.util.function.DoubleSupplier;

/** Optional-DH-safe, render-thread scope shared with Iris' late uniform upload. */
public final class DhPortalShaderCoverage {
    public static final String UNIFORM = "ipDhCoverageBlocks";
    private static final DhScopedContext<DoubleSupplier> COVERAGE = new DhScopedContext<>();
    private static long disabledUntil;
    private static String lastSample = "No supported portal DH sample yet";
    private static long sampledAt;
    private static long uniformUploads;
    private DhPortalShaderCoverage() {}

    public static DhScopedContext.Scope enter(DoubleSupplier coverage) { return COVERAGE.push(coverage); }
    public static boolean enabled() { return enabled(System.nanoTime(), disabledUntil); }
    static boolean enabled(long now, long until) { return until == 0 || now - until >= 0; }
    public static void enable() { disabledUntil = 0; }
    public static void disableForSeconds(int seconds) {
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Duration must be 1..60 seconds");
        disabledUntil = System.nanoTime() + seconds * 1_000_000_000L;
    }
    public static double current() {
        var supplier = COVERAGE.current();
        double coverage = supplier == null ? -1 : supplier.getAsDouble();
        return enabled() ? coverage : -1;
    }
    public static boolean eligible(int layer, boolean sourceRefresh, boolean shadow) {
        return layer == 1 && !sourceRefresh && !shadow;
    }
    /** End the dither transition before full-detail terrain ends, retaining a one-block overlap. */
    public static double fadeEnd(double far, double coverage) {
        return coverage < 0 ? far * .6 : Math.min(far * .6, Math.max(0, coverage - 1));
    }
    public static void sample(String description) { lastSample = description; sampledAt = System.nanoTime(); }
    public static String diagnostics() {
        long age = sampledAt == 0 ? -1 : (System.nanoTime() - sampledAt) / 1_000_000;
        return "DH portal coverage enabled=" + enabled() + " sampleAgeMs=" + age
            + " uniformUploads=" + uniformUploads + " " + lastSample
            + " adapters=" + DhPortalShaderPackAdapter.status();
    }
    /** Called after Iris has filled this exact program, also on main draws to clear old portal values. */
    public static void bind() {
        int program = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int location = GL20.glGetUniformLocation(program, UNIFORM);
        if (location >= 0) {
            GL20.glUniform1f(location, (float)current());
            uniformUploads++;
        }
    }
}
