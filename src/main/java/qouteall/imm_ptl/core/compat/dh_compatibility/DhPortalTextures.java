package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Continuous mip selection confined to one 16x16 block tile in DH's atlas. */
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

                vec4 ipSampleTileMip(vec2 uv, vec2 atlasSize, float mip) {
                    vec2 origin = vec2(float(vTextureTileId % 256u), float(vTextureTileId / 256u)) * 16.0;
                    // Keep each mip's filter footprint inside this tile, including
                    // inverted faces whose UV can land exactly on the far edge.
                    float inset = 0.5 * exp2(mip);
                    vec2 pixel = clamp(uv * atlasSize, origin + inset, origin + 16.0 - inset);
                    return textureLod(uBlockAtlas, pixel / atlasSize, mip);
                }

                vec4 ipSampleBlockTile(vec2 uv, vec2 atlasSize) {
                    if (!uIpContinuousTextureGradients) return texture(uBlockAtlas, uv);
                    vec2 dx = ipFaceGradient(dFdx(vBlockPos)) * 16.0;
                    vec2 dy = ipFaceGradient(dFdy(vBlockPos)) * 16.0;
                    // Derive the footprint before fract(). At mip 4 one 16x16
                    // tile is one texel; coarser mips mix unrelated materials.
                    float mip = clamp(log2(max(max(length(dx), length(dy)), 1.0)), 0.0, 4.0);
                    return mix(ipSampleTileMip(uv, atlasSize, floor(mip)),
                               ipSampleTileMip(uv, atlasSize, ceil(mip)), fract(mip));
                }

                """ + insertion);
    }
}
