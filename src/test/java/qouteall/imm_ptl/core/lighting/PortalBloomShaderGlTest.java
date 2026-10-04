package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Real mipmapped colour leakage, using the production GLSL filter and hidden driver context. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class PortalBloomShaderGlTest {
    static long window;
    int program, texture, vao;
    @BeforeAll static void context() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(8, 8, "Portal aperture bloom", 0, 0);
        assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
    }
    @AfterAll static void finish() {
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }
    @BeforeEach void setup() throws Exception {
        String helper;
        try (var in = getClass().getResourceAsStream("/assets/immersive_portals/shaders/portal_bloom.glsl")) {
            assertNotNull(in); helper = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        program = PortalLightGpuTest.link("#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}",
            "#version 330 core\n#define texture2DLod textureLod\nout vec4 result;uniform sampler2D source;uniform vec2 probe;uniform int mode;\n" + helper + """
            void main() {
                vec3 color;
                if (ipBloomEdgeCount == 0) color = textureLod(source, probe, 6.0).rgb;
                else if (mode == 1) color = ipBloomFallback(source, probe, vec2(64));
                else if (mode == 3) color = textureLod(source, probe, 3.0).rgb;
                else if (mode == 2) {
                    vec3 sum = vec3(0); float total = 0;
                    for (int i = -3; i <= 3; i++) {
                        vec3 tap;
                        if (ipBloomTap(source, probe + vec2(float(i) * .1, 0), vec2(64), 6.0, tap)) {
                            float weight = 4.0 - abs(float(i)); sum += tap * weight; total += weight;
                        }
                    }
                    color = total > 0 ? sum / total : ipBloomFallback(source, probe, vec2(64));
                } else if (!ipBloomTap(source, probe, vec2(64), 6.0, color)) color = vec3(0);
                result = vec4(color, 1);
            }
            """);
        glUseProgram(program); vao = glGenVertexArrays(); glBindVertexArray(vao); glViewport(0, 0, 8, 8);
        texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        float[] pixels = new float[64 * 64 * 4];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            boolean inside = x >= 16 && x < 48 && y >= 16 && y < 48;
            int i = (y * 64 + x) * 4;
            pixels[i] = inside ? 0 : 1; pixels[i + 2] = inside ? 1 : 0; pixels[i + 3] = 1;
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 64, 64, 0, GL_RGBA, GL_FLOAT, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
        glUniform1i(glGetUniformLocation(program, "source"), 0);
        glUniform1i(glGetUniformLocation(program, "ipBloomEdgeCount"), 4);
        glUniform3fv(glGetUniformLocation(program, "ipBloomEdges[0]"), new float[]{1,0,-16, -1,0,48, 0,1,-16, 0,-1,48});
        glUniform2f(glGetUniformLocation(program, "ipBloomInterior"), 32, 32);
    }
    @AfterEach void cleanup() {
        glUseProgram(0); glBindVertexArray(0); glDeleteVertexArrays(vao); glDeleteTextures(texture); glDeleteProgram(program);
    }
    float[] sample(float x, float y, int mode) {
        glUniform2f(glGetUniformLocation(program, "probe"), x, y);
        glUniform1i(glGetUniformLocation(program, "mode"), mode);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4]; glReadPixels(4, 4, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        assertEquals(GL_NO_ERROR, glGetError()); return pixel;
    }
    void blue(float[] pixel) { assertEquals(0, pixel[0], .01f); assertEquals(1, pixel[2], .01f); }
    @Test void insideTapCannotImportRedThroughItsMipFootprint() {
        blue(sample(.5f, .5f, 0)); blue(sample(.29f, .5f, 0));
    }
    @Test void discardedOutsideTapsAreRenormalizedWithoutDarkeningConstantBlue() {
        blue(sample(.29f, .5f, 2)); blue(sample(.72f, .5f, 2));
    }
    @Test void coarseTileMissingTheApertureExtendsSafeBlueInsteadOfBlackOrOutsideRed() {
        blue(sample(0, 0, 1)); blue(sample(1, 1, 1)); blue(sample(-.3f, .5f, 2));
    }
    @Test void rootViewKeepsTheNativeMipmapResult() {
        glUniform1i(glGetUniformLocation(program, "ipBloomEdgeCount"), 0);
        var pixel = sample(.5f, .5f, 0);
        assertEquals(.75f, pixel[0], .01f); assertEquals(.25f, pixel[2], .01f);
    }
    @Test void diagonalUnalignedEdgeExcludesTheFullBilinearMipFootprint() {
        // At this sample the aperture distance is 12.3237 pixels. The old sqrt(2)
        // rule admitted LOD 3, whose bilinear support includes red base pixels
        // across the diagonal. The corrected bound selects at most LOD 2.
        float boundary = 51.0116443f;
        float[] pixels = new float[64 * 64 * 4];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            boolean inside = x + .5f + y + .5f >= boundary;
            int i = (y * 64 + x) * 4;
            pixels[i] = inside ? 0 : 100; // HDR scenery makes even a small leaking mip weight visible.
            pixels[i + 2] = inside ? 1 : 0; pixels[i + 3] = 1;
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 64, 64, 0, GL_RGBA, GL_FLOAT, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
        float normal = (float) (1 / Math.sqrt(2));
        glUniform1i(glGetUniformLocation(program, "ipBloomEdgeCount"), 5);
        glUniform3fv(glGetUniformLocation(program, "ipBloomEdges[0]"), new float[]{
            normal, normal, -boundary * normal, 1,0,0, -1,0,64, 0,1,0, 0,-1,64});
        glUniform2f(glGetUniformLocation(program, "ipBloomInterior"), 48, 48);
        float uv = 34.22f / 64;
        assertTrue(sample(uv, uv, 3)[0] > .1f, "The actual mipmap must reproduce outside HDR contamination at LOD 3");
        float[] safe = sample(uv, uv, 0);
        assertEquals(0, safe[0], .001f, "A diagonal opening must reject every outside contributing base pixel");
        assertEquals(1, safe[2], .01f);
    }
}
