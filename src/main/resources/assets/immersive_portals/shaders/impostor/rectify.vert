#version 330 core
uniform mat4 clipFromUv;
out vec4 sourceClip;
const vec2 corners[6] = vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
void main() {
    vec2 uv = corners[gl_VertexID];
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    sourceClip = clipFromUv * vec4(uv, 0.0, 1.0);
}
