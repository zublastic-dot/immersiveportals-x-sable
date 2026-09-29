package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Small runtime adaptation of DH's own shader; no bundled copy of its renderer. */
public final class DhPortalSsao {
    public static final String APPLY_SHADER = "assets/distanthorizons/shaders/ssao/gl/apply.frag";

    private DhPortalSsao() {}

    public static String patchApplyShader(String source) {
        String sample = "linearizeDepth(sampleDepth)";
        String center = "linearizeDepth(fragmentDepth)";
        String insertion = "float Gaussian(";
        // Leave an unknown shader intact. The uniform hook also checks availability.
        if (!source.contains(sample) || !source.contains(center) || !source.contains(insertion)) return source;
        return source.replace(sample, "ipViewDepth(sampleTexCoord, sampleDepth)")
            .replace(center, "ipViewDepth(texCoord, fragmentDepth)")
            .replace(insertion, """
                uniform bool uIpPortalProjection;
                uniform bool uIpDepthZeroToOne;
                uniform mat4 uIpInverseProjection;

                float ipViewDepth(vec2 uv, float depth) {
                    if (!uIpPortalProjection) return linearizeDepth(depth);
                    // Match the clamped depth texel at the framebuffer edge.
                    vec2 halfPixel = 0.5 / uViewSize;
                    uv = clamp(uv, halfPixel, 1.0 - halfPixel);
                    float z = uIpDepthZeroToOne ? depth : depth * 2.0 - 1.0;
                    vec4 eye = uIpInverseProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
                    return -eye.z / eye.w;
                }

                """ + insertion);
    }
}
