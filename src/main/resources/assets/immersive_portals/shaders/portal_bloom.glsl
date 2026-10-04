// IP_PORTAL_BLOOM_APERTURE_V1
// Original aperture filter; the shader pack's kernel and colour equations remain its own.
uniform int ipBloomEdgeCount;
uniform vec3 ipBloomEdges[32];
uniform vec2 ipBloomInterior;

float ipBloomDistance(vec2 pixel) {
    float distanceToEdge = 1e20;
    for (int edge = 0; edge < ipBloomEdgeCount; ++edge)
        distanceToEdge = min(distanceToEdge, dot(ipBloomEdges[edge], vec3(pixel, 1.0)));
    return distanceToEdge;
}

bool ipBloomTap(sampler2D source, vec2 uv, vec2 size, float wantedLod, out vec3 color) {
    float distanceToEdge = ipBloomDistance(uv * size);
    if (distanceToEdge < 1.5) { color = vec3(0.0); return false; }
    // A tap centre inside the aperture is insufficient: implicit mipmaps can
    // contain lava or sky outside it. Bound the entire bilinear mip footprint.
    // Bilinear interpolation spans two box-filtered mip texels. Their base-pixel
    // centres can reach 1.5 * mipScale - 0.5 along each axis; a diagonal edge
    // therefore needs the conservative 1.5 * sqrt(2) factor, not sqrt(2).
    // Level zero is separately safe at the 1.5-pixel admission margin above.
    float safeLod = max(0.0, floor(log2(max((distanceToEdge - 1.0) / 2.121321, 1.0))));
    color = texture2DLod(source, uv, min(wantedLod, safeLod)).rgb;
    return true;
}

vec3 ipBloomFallback(sampler2D source, vec2 uv, vec2 size) {
    // Coarse tiles sometimes miss a narrow aperture. Extend a safe inside
    // sample instead of black, so the pack's later bilinear upsampling cannot
    // darken a uniformly coloured room at the aperture edge.
    vec2 delta = uv * size - ipBloomInterior;
    float along = 1.0;
    for (int edge = 0; edge < ipBloomEdgeCount; ++edge) {
        vec3 plane = ipBloomEdges[edge];
        float slope = dot(plane.xy, delta);
        if (slope < 0.0)
            along = min(along, max(0.0, (dot(plane, vec3(ipBloomInterior, 1.0)) - 1.5) / -slope));
    }
    return texture2DLod(source, (ipBloomInterior + delta * along) / size, 0.0).rgb;
}
