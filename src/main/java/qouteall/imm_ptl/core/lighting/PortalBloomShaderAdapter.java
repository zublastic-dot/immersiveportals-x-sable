package qouteall.imm_ptl.core.lighting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Alters only the supported pack's bloom sampling support, before Iris preprocessing. */
final class PortalBloomShaderAdapter {
    private static final String MARKER = "IP_PORTAL_BLOOM_APERTURE_V1";
    private static final String SIGNATURE = "vec3 BloomTile(float lod, vec2 offset, vec2 scaledCoord) {";
    private static final String TAP = "bloom += texture2D(colortex0, bloomCoord).rgb * wg;";
    private static final String NORMALIZE = "bloom /= 4096.0;";
    private static final String PADDING = "float padding = 0.5 + 0.005 * scale;";

    private PortalBloomShaderAdapter() {}

    static List<String> patch(String pack, String path, List<String> input) {
        if (!PortalShaderPackAdapter.supports(pack) || input == null || !path.endsWith("/composite4.fsh")) return input;
        String source = String.join("\n", input);
        if (source.length() > 8 * 1024 * 1024 || source.contains(MARKER)
            || !source.contains("// Complementary Shaders by EminGT")) return input;
        int start = source.indexOf(SIGNATURE);
        if (start < 0 || source.indexOf(SIGNATURE, start + 1) >= 0) return input;
        int depth = 1, end = start + SIGNATURE.length();
        while (end < source.length() && depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++; else if (c == '}') depth--;
        }
        if (depth != 0) return input;
        String function = source.substring(start, end);
        if (!once(function, TAP) || !once(function, NORMALIZE) || !once(function, PADDING)) return input;
        try (var stream = PortalBloomShaderAdapter.class.getResourceAsStream(
            "/assets/immersive_portals/shaders/portal_bloom.glsl")) {
            if (stream == null) return input;
            String helper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            function = function.replace(PADDING, PADDING + "\n"
                + "    float ipBloomWeight = 0.0;\n"
                + "    vec2 ipBloomDx = dFdx(coord) * view, ipBloomDy = dFdy(coord) * view;\n"
                + "    float ipBloomLod = max(0.0, 0.5 * log2(max(dot(ipBloomDx, ipBloomDx), dot(ipBloomDy, ipBloomDy))));\n")
                .replace(TAP, "if (ipBloomEdgeCount == 0) { " + TAP + " } else {\n"
                    + "                    vec3 ipBloomColor;\n"
                    + "                    if (ipBloomTap(colortex0, bloomCoord, view, ipBloomLod, ipBloomColor)) {\n"
                    + "                        bloom += ipBloomColor * wg; ipBloomWeight += wg;\n"
                    + "                    }\n                }")
                .replace(NORMALIZE, "if (ipBloomEdgeCount == 0) { " + NORMALIZE + " }\n"
                    + "        else bloom = ipBloomWeight > 0.0 ? bloom / ipBloomWeight : ipBloomFallback(colortex0, coord, view);");
            return List.copyOf((source.substring(0, start) + helper + "\n" + function + source.substring(end)).lines().toList());
        } catch (IOException unavailable) { return input; }
    }

    private static boolean once(String source, String anchor) {
        int index = source.indexOf(anchor);
        return index >= 0 && source.indexOf(anchor, index + 1) < 0;
    }
}
