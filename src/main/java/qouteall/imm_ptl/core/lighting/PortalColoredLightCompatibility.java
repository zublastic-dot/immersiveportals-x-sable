package qouteall.imm_ptl.core.lighting;

/** One admission rule shared by optional mixin discovery and runtime field publication. */
public final class PortalColoredLightCompatibility {
    public static boolean supports(String version) { return "2.5.1".equals(version); }
    private PortalColoredLightCompatibility() {}
}
