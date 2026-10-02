#version 330 core
uniform sampler2D sourceColor;
uniform sampler2D sourceCoverage;
uniform bool useCoverage;
in vec4 sourceClip;
out vec4 color;
void main() {
    if (sourceClip.w <= 0.0) discard;
    vec2 uv = sourceClip.xy / sourceClip.w * 0.5 + 0.5;
    if (any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0)))) discard;
    // Match color and coverage at one source pixel. A LINEAR color sampler must
    // never pull foreground pixels from outside the aperture's stencil mask.
    ivec2 size = textureSize(sourceColor,0);
    ivec2 pixel = clamp(ivec2(uv * vec2(size)),ivec2(0),size - 1);
    if (useCoverage && texelFetch(sourceCoverage,pixel,0).r < 0.5) discard;
    color = vec4(texelFetch(sourceColor,pixel,0).rgb,1.0);
}
