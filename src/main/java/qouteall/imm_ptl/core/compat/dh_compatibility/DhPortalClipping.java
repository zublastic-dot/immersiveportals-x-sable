package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Clip geometry at the portal without compressing DH's depth into an oblique near plane. */
public final class DhPortalClipping {
    public static final String TERRAIN_VERTEX = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    public static final String DIRECT_VERTEX = "assets/distanthorizons/shaders/generic/gl/direct/vert.vert";
    public static final String INSTANCED_VERTEX = "assets/distanthorizons/shaders/generic/gl/instanced/vert.vert";
    public static final String DIRECT_FRAGMENT = "assets/distanthorizons/shaders/generic/gl/direct/frag.frag";
    public static final String INSTANCED_FRAGMENT = "assets/distanthorizons/shaders/generic/gl/instanced/frag.frag";
    public static final String UNIFORM = "uIpPortalClipPlane";
    private static final String VARYING = "ipPortalClipDistance";
    private static final Pattern MAIN = Pattern.compile("void main\\(\\)\\s*\\{");
    private static final Map<String, String> VERTICES = Map.of(
        TERRAIN_VERTEX, "gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);",
        DIRECT_VERTEX, "gl_Position = uTransform * vec4(vPosition, 1.0);",
        INSTANCED_VERTEX, "gl_Position = uProjectionMvm * transform * vec4(vPosition, 1.0);"
    );
    public static final Set<String> PATHS = Set.of(TERRAIN_VERTEX, DIRECT_VERTEX, INSTANCED_VERTEX,
        DhPortalTextures.TERRAIN_SHADER, DIRECT_FRAGMENT, INSTANCED_FRAGMENT);

    private DhPortalClipping() {}

    public static Vector4f clipSpacePlane(Matrix4fc projection, Matrix4fc modelView, Vector4fc cameraPlane) {
        Vector4f result = new Matrix4f(projection).mul(modelView).invert().transpose()
            .transform(new Vector4f(cameraPlane));
        return result.isFinite() ? result : null;
    }

    public static boolean isPatched(String source) { return source.contains(VARYING); }

    public static String patch(String path, String source) {
        if (isPatched(source) || !PATHS.contains(path)) return source;
        var main = MAIN.matcher(source);
        if (!main.find()) return source;
        String position = VERTICES.get(path);
        if (position != null) {
            if (!source.contains(position)) return source;
            // Assign immediately after the actual position calculation, before optional TAA jitter.
            return source.substring(0, main.start()) + "uniform vec4 " + UNIFORM + ";\nout float " + VARYING + ";\n"
                + source.substring(main.start()).replace(position,
                    position + "\n    " + VARYING + " = dot(" + UNIFORM + ", gl_Position);");
        }
        if (path.equals(DhPortalTextures.TERRAIN_SHADER)) {
            if (!source.contains("in vec3 vertexWorldPos;") || !source.contains("flat in uint vNormalIndex;")) return source;
        } else if (!source.contains("in vec4 fColor;") || !source.contains("fragColor = fColor;")) return source;
        return source.substring(0, main.start()) + "in float " + VARYING + ";\n"
            + source.substring(main.start(), main.end()) + "\n    if (" + VARYING + " < 0.0) discard;"
            + source.substring(main.end());
    }
}
