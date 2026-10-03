package qouteall.imm_ptl.core.teleportation;

/** Audio-executor state: retain the final native authored gain while an aperture is unavailable. */
public final class PortalSoundGain {
    private float authored = 1;
    private boolean muted;

    public float nativeWrite(float gain) {
        authored = gain;
        return output();
    }

    public boolean muted() { return muted; }
    public float setMuted(boolean value) { muted = value; return output(); }
    public void reset() { authored = 1; muted = false; }
    private float output() { return muted ? 0 : authored; }
}
