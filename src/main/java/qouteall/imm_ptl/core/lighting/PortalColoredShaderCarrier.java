package qouteall.imm_ptl.core.lighting;

/** Supplies the missing brightness input to Colorful's existing Complementary hue replacement. */
public final class PortalColoredShaderCarrier {
    public static final String MARKER = "IP_PORTAL_RGB_CARRIER_V1";
    private static final String MAIN = "void main() {";
    private static final String CALL = "DoLighting(color,";
    private static final String VARYING = "in vec3 colorfulLightingSodiumCompat_Color;";
    private static final String HELPER = """
        // IP_PORTAL_RGB_CARRIER_V1
        uniform int ipPortalRgbCarrierCount;
        uniform vec3 ipPortalRgbCarrierMin[4], ipPortalRgbCarrierMax[4];
        float ipPortalRgbCarrier(vec3 point, vec3 rgb, float original) {
            for (int i = 0; i < min(ipPortalRgbCarrierCount, 4); ++i) {
                if (all(greaterThanEqual(point, ipPortalRgbCarrierMin[i]))
                    && all(lessThanEqual(point, ipPortalRgbCarrierMax[i]))) {
                    // Colorful's native Vanilla UV2 decoder uses max(R,G,B)>>4.
                    // Complementary's GetLightMapCoordinates normalizes those 0..15 levels.
                    float level = floor(clamp(max(max(rgb.r, rgb.g), rgb.b), 0.0, 1.0) * 255.0 / 16.0);
                    return max(original, level / 15.0);
                }
            }
            return original;
        }
        """;

    private PortalColoredShaderCarrier() {}

    /** Runs after Colorful 2.5.1 has decoded the vertex hue and patched the supported pack. */
    public static String patch(String pack, String nativeFragment) {
        if (!PortalShaderPackAdapter.supports(pack) || nativeFragment == null || nativeFragment.length() > 8 * 1024 * 1024
            || nativeFragment.contains(MARKER) || !once(nativeFragment, MAIN) || !once(nativeFragment, CALL)
            || !once(nativeFragment, VARYING)
            || !nativeFragment.contains("colorfulLightingSodiumCompat_PackBlocklight")
            || !nativeFragment.contains("vec2 lmCoordM = lmCoord;")
            || !nativeFragment.contains("playerPos")) return nativeFragment;
        int call = nativeFragment.indexOf(CALL);
        int end = nativeFragment.indexOf(';', call);
        if (end < 0 || !nativeFragment.substring(call, end).contains("lmCoordM")) return nativeFragment;
        return nativeFragment.replace(MAIN, HELPER + "\n" + MAIN).replace(CALL,
            "lmCoordM.x = ipPortalRgbCarrier(playerPos, colorfulLightingSodiumCompat_Color, lmCoordM.x);\n    " + CALL);
    }

    private static boolean once(String source, String anchor) {
        int position = source.indexOf(anchor);
        return position >= 0 && source.indexOf(anchor, position + 1) < 0;
    }
}
