package qouteall.imm_ptl.core.compat.dh_compatibility;

import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Changes only the known pack's DH alpha handoff; no fog, projection, or sunlight edits. */
public final class DhPortalShaderPackAdapter {
    static final String MARKER = "// IP_DH_PORTAL_COVERAGE_V1";
    static final String ANCHOR = "color.a *= smoothstep(far * 0.4, far * 0.6, lengthCylinder);";
    static final String HELPER = """
        // IP_DH_PORTAL_COVERAGE_V1
        uniform float ipDhCoverageBlocks;
        float ipDhPortalFade(float distanceToCamera, float nativeFar) {
            if (ipDhCoverageBlocks < 0.0) return smoothstep(nativeFar * 0.4, nativeFar * 0.6, distanceToCamera);
            if (ipDhCoverageBlocks >= nativeFar * 0.6 + 1.0)
                return smoothstep(nativeFar * 0.4, nativeFar * 0.6, distanceToCamera);
            float end = max(0.0, ipDhCoverageBlocks - 1.0);
            if (end <= 0.0) return 1.0;
            return smoothstep(end * (2.0 / 3.0), end, distanceToCamera);
        }
        """;
    private static final Map<String, String> STATUS = new ConcurrentHashMap<>();
    private DhPortalShaderPackAdapter() {}
    public static Map<String,String> status() { return Map.copyOf(STATUS); }
    public static void clear() { STATUS.clear(); }
    public static boolean admitted(String pack) {
        // Iris chooses the program directory, including custom dimension mappings.
        // The live program's uniform is the final admission check in bind(): an
        // unadapted terrain/water program never queries or consumes the coverage.
        return PortalShaderPackAdapter.supports(pack) && STATUS.containsValue("adapted");
    }
    private static boolean terrainProgramPath(String path) {
        if (path == null || path.length() > 1024
            || !path.matches("/(?:[A-Za-z0-9_.-]+/)*dh_(?:terrain|water)\\.fsh")) return false;
        for (String segment : path.split("/"))
            if (segment.equals(".") || segment.equals("..")) return false;
        return true;
    }
    public static List<String> patch(String pack, String path, List<String> original) {
        if (!PortalShaderPackAdapter.supports(pack)
            || !terrainProgramPath(path)) return original;
        String source = String.join("\n", original);
        if (source.contains(MARKER)) return original;
        if (source.length() > 8 * 1024 * 1024) return original;
        int at = source.indexOf(ANCHOR);
        int main = source.indexOf("void main() {");
        if (!source.contains("// Complementary Shaders by EminGT") || at < 0
            || source.indexOf(ANCHOR, at + 1) >= 0 || main < 0 || main > at) {
            STATUS.put(path, "unchanged: DH fade signature not unique");return original;
        }
        // Place the declaration immediately before main: after all includes/macros,
        // outside main, and after #version even when an extension follows it.
        source = source.substring(0, main) + HELPER + source.substring(main);
        source = source.replace(ANCHOR, "color.a *= ipDhPortalFade(lengthCylinder, far);");
        STATUS.put(path, "adapted");
        return List.of(source.split("\n", -1));
    }
}
