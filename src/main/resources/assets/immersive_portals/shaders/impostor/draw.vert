#version 330 core
uniform mat4 clipFromUv;
out vec2 uv;
const vec2 corners[6] = vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
void main() {
    uv = corners[gl_VertexID];
    gl_Position = clipFromUv * vec4(uv,0.0,1.0);
}
