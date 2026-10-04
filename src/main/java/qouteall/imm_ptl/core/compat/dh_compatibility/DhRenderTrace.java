package qouteall.imm_ptl.core.compat.dh_compatibility;

import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in diagnostics only: no Minecraft, DH, Iris or GL linkage when not installed. */
public final class DhRenderTrace {
    static final int MAX_KEYS = 48, MAX_SAMPLES = 240, MAX_TEXT = 12_000;
    static final long SAMPLE_NANOS = 500_000_000L;
    static final Window WINDOW = new Window();
    private DhRenderTrace() {}

    public static String arm(int seconds) {
        WINDOW.arm(seconds, System.nanoTime());
        return "DH render trace armed for " + seconds + " seconds (read-only, at most " + MAX_SAMPLES + " samples)";
    }
    public static long captureId() { return WINDOW.captureId; }
    public static boolean active() { return WINDOW.active(System.nanoTime()); }
    public static boolean reserve(String key) { return WINDOW.reserve(key, System.nanoTime()); }
    public static void record(String key, String text) { WINDOW.record(key, text); }
    public static String stop() { WINDOW.armed = false; return diagnostics(); }
    public static String diagnostics() { return WINDOW.report(System.nanoTime()); }

    static final class Window {
        boolean armed;
        long deadline, captureId;
        int samples;
        final Map<String, Long> last = new LinkedHashMap<>();
        final Map<String, String> latest = new LinkedHashMap<>();
        void arm(int seconds, long now) {
            if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("Trace duration must be 1..30 seconds");
            captureId++;
            deadline = now + seconds * 1_000_000_000L;
            samples = 0; last.clear(); latest.clear(); armed = true;
        }
        boolean active(long now) {
            return armed && now - deadline < 0 && samples < MAX_SAMPLES;
        }
        boolean reserve(String key, long now) {
            if (!active(now)) return false;
            Long previous = last.get(key);
            if (previous != null && now - previous < SAMPLE_NANOS) return false;
            if (previous == null && last.size() >= MAX_KEYS) return false;
            last.put(key, now); samples++; return true;
        }
        void record(String key, String text) {
            // Only keys with a granted budget may retain diagnostic text.
            if (!last.containsKey(key)) return;
            latest.put(key, text.substring(0, Math.min(MAX_TEXT, text.length())));
        }
        String report(long now) {
            return "DH render trace active=" + active(now) + " samples=" + samples + "/" + MAX_SAMPLES
                + " keys=" + latest.size() + "/" + MAX_KEYS + "\n" + String.join("\n", latest.values());
        }
    }
}
