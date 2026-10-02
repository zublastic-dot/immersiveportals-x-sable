package qouteall.imm_ptl.core.render.impostor;

/** Pure distance and transition policy. Renderer eligibility and image validity are external. */
public final class PortalImpostorPolicy {
    public static final double DEFAULT_LIVE_CUTOFF = 128.0;
    public static final int DEFAULT_RESOLUTION = 256;
    public static final long REENTRY_BLEND_NANOS = 200_000_000L;

    private PortalImpostorPolicy() {}

    /**
     * Begin caching before the renderer's hard range limit. Zero disables cached
     * distance admission when that limit is unknown or invalid.
     */
    public static double liveCutoff(double configured, double engineRange) {
        if (!Double.isFinite(engineRange) || engineRange <= 0.0) return 0.0;
        double requested = Double.isFinite(configured) && configured > 0.0
            ? Math.max(8.0, configured) : DEFAULT_LIVE_CUTOFF;
        // Do not apply the normal eight-block floor after the engine cap: very
        // short engine ranges must still leave time to obtain the last live frame.
        return Math.min(requested, engineRange * 0.85);
    }

    /** Distance hysteresis only; callers must additionally require a valid image. */
    public static boolean useCached(boolean previouslyFar, double distance, double cutoff) {
        if (!Double.isFinite(distance) || distance < 0.0
            || !Double.isFinite(cutoff) || cutoff <= 0.0) return false;
        double threshold = previouslyFar ? cutoff - Math.min(8.0, cutoff * 0.25) : cutoff;
        return distance > threshold;
    }

    public static int resolution(int configured) {
        if (configured <= 0) return DEFAULT_RESOLUTION;
        return Math.max(64, Math.min(512, configured));
    }

    /** Cached alpha is an overlay weight; renderLive remains true throughout re-entry. */
    public record Decision(boolean renderLive, boolean drawCached, float cachedAlpha) {}

    /**
     * Per-image state. A completed fresh live world render, not elapsed time alone,
     * permits the old image to fade out. The manager must retain that old image
     * until drawCached becomes false, and enforce its own hard render range.
     * Full-quad texture recapture is deliberately not a prerequisite: a near or
     * partially offscreen aperture can have a valid live underlay without one.
     */
    public static final class State {
        private static final Decision LIVE = new Decision(true, false, 0.0f);
        private static final Decision CACHED = new Decision(false, true, 1.0f);
        private static final Decision WAITING = new Decision(true, true, 1.0f);

        private boolean far;
        private boolean waitingForLive;
        private boolean blending;
        private long blendStartedNanos;

        public Decision decide(double distance, double cutoff, boolean hasImage, long nowNanos) {
            if (!hasImage || !Double.isFinite(distance) || distance < 0.0
                || !Double.isFinite(cutoff) || cutoff <= 0.0) {
                reset();
                return LIVE;
            }
            if (useCached(far, distance, cutoff)) {
                far = true;
                waitingForLive = false;
                blending = false;
                return CACHED;
            }
            if (far) {
                far = false;
                waitingForLive = true;
                blending = false;
            }
            if (waitingForLive) return WAITING;
            if (!blending) return LIVE;

            // Subtraction also handles a nanoTime origin wrapping through zero.
            // A backwards clock cannot prematurely discard the old image.
            long elapsed = nowNanos - blendStartedNanos;
            if (elapsed >= REENTRY_BLEND_NANOS) {
                blending = false;
                return LIVE;
            }
            double progress = Math.max(0.0, (double) elapsed / REENTRY_BLEND_NANOS);
            double smooth = progress * progress * (3.0 - 2.0 * progress);
            return new Decision(true, true, (float) (1.0 - smooth));
        }

        /** Call only after a complete, successful live world render for this aperture. */
        public void liveRendered(long nowNanos) {
            if (waitingForLive) {
                waitingForLive = false;
                blending = true;
                blendStartedNanos = nowNanos;
            }
        }

        public boolean isFar() {
            return far;
        }

        public void reset() {
            far = false;
            waitingForLive = false;
            blending = false;
            blendStartedNanos = 0L;
        }
    }
}
