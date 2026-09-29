package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Kept free of client/DH types: the mixin plugin also runs without DH installed. */
public final class DhCompatibility {
    private DhCompatibility() {}

    public static boolean supports(String version) {
        return "3.3.2".equals(version) || "3.3.2-1.21.1".equals(version)
            || "3.3.3".equals(version) || "3.3.3-1.21.1".equals(version);
    }
}
