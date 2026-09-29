package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Keep mip selection continuous when DH repeats a block tile with fract(). */
public final class DhPortalTextures {
    public static final String TERRAIN_SHADER = "assets/distanthorizons/shaders/terrain/gl/frag.frag";
    private DhPortalTextures() {}

    public static String patchTerrainShader(String source) {
        if (source.contains("vec4 ipSampleBlockTile(")) return source;
        String sample = "texture(uBlockAtlas, uv)";
        String insertion = "void main()";
        if (!source.contains(sample) || !source.contains(insertion)) return source;
        return source.replace(sample, "ipSampleBlockTile(uv, vec2(atlasSize))")
            .replace(insertion, """
                uniform bool uIpContinuousTextureGradients;

                vec2 ipFaceGradient(vec3 delta) {
                    if (vNormalIndex == 0u) return vec2(delta.x, -delta.z);
                    if (vNormalIndex == 1u) return delta.xz;
                    if (vNormalIndex == 2u) return -delta.xy;
                    if (vNormalIndex == 3u) return vec2(delta.x, -delta.y);
                    if (vNormalIndex == 4u) return vec2(delta.z, -delta.y);
                    return -delta.zy;
                }

                vec4 ipSampleBlockTile(vec2 uv, vec2 atlasSize) {
                    if (!uIpContinuousTextureGradients) return texture(uBlockAtlas, uv);
                    // Derivatives of wrapped UVs jump at integer block boundaries.
                    // Derive the footprint before wrapping, retaining the same tile/UV.
                    vec2 dx = ipFaceGradient(dFdx(vBlockPos)) * 16.0 / atlasSize;
                    vec2 dy = ipFaceGradient(dFdy(vBlockPos)) * 16.0 / atlasSize;
                    return textureGrad(uBlockAtlas, uv, dx, dy);
                }

                """ + insertion);
    }
}
