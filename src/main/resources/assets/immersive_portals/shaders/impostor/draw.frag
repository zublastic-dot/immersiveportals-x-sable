#version 330 core
uniform sampler2D image;
uniform float opacity;
in vec2 uv;
out vec4 color;
void main() {
    vec4 sampled = texture(image,uv);
    if (sampled.a < 0.995) discard;
    color = vec4(sampled.rgb,opacity);
}
