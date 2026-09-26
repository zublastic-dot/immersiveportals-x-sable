package qouteall.imm_ptl.core.compat.real_camera;

/** The optional hooks are verified against the 1.21.1 NeoForge release bytecode. */
public final class RealCameraCompatibility {
    private RealCameraCompatibility() {}

    public static boolean supports(String version) {
        return "0.7.8-beta".equals(version);
    }
}
