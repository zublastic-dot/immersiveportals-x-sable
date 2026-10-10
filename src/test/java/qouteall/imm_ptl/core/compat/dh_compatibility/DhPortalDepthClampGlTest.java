package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Real depth writes followed by the supported pack's depth-zero sky classification. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhPortalDepthClampGlTest {
    private static long window;
    private static int terrain, composite, vao, framebuffer, color, depth;
    private static final String VERTEX = """
        #version 330 core
        uniform float clipZ;
        out vec2 uv;
        void main() {
            uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(uv * 2.0 - 1.0, clipZ, 1.0);
        }
        """;

    @BeforeAll static void setup() {
        assertTrue(glfwInit()); glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(8, 8, "DH portal depth clamp regression", 0, 0);
        assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
        terrain = program(VERTEX, "#version 330 core\nuniform float distanceToCamera;\n"
            + "uniform vec3 surface;\nout vec4 result;\n" + DhPortalShaderPackAdapter.HELPER + """
            void main() {
                if (ipDhPortalFade(distanceToCamera, 112.0) < 0.5) discard;
                result = vec4(surface, 1.0);
            }
            """);
        composite = program(VERTEX, """
            #version 330 core
            uniform sampler2D terrainColor, terrainDepth;
            in vec2 uv;
            out vec4 result;
            void main() {
                float z0lod = texture(terrainDepth, uv).r;
                // Complementary deferred1: depth zero is sky, not LOD terrain.
                result = z0lod < 1.0 && z0lod > 0.0
                    ? texture(terrainColor, uv) : vec4(0.5, 0.0, 0.75, 1.0);
            }
            """);
        vao = glGenVertexArrays(); framebuffer = glGenFramebuffers();
        color = texture(GL_RGBA8, GL_RGBA); depth = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static int program(String vertex, String fragment) {
        int program = glCreateProgram();
        for (int type : new int[]{GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            glShaderSource(shader, type == GL_VERTEX_SHADER ? vertex : fragment); glCompileShader(shader);
            assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
            glAttachShader(program, shader); glDeleteShader(shader);
        }
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        return program;
    }

    private static int texture(int format, int channels) {
        int texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, format, 8, 8, 0, channels, GL_FLOAT, (java.nio.FloatBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    @BeforeEach void reset() {
        glDisable(GL_DEPTH_CLAMP); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_DITHER);
        glDepthMask(true); glDepthFunc(GL_LESS); glDepthRange(0, 1);
        glColorMask(true, true, true, true); glViewport(0, 0, 8, 8); glBindVertexArray(vao);
    }

    @AfterEach void noGlError() {
        glDisable(GL_DEPTH_CLAMP); glBindFramebuffer(GL_FRAMEBUFFER, 0);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @AfterAll static void cleanup() {
        glUseProgram(0); glDeleteProgram(terrain); glDeleteProgram(composite);
        glDeleteTextures(color); glDeleteTextures(depth); glDeleteFramebuffers(framebuffer); glDeleteVertexArrays(vao);
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }

    private record Pixel(float depth, float[] rgba) {}

    private Pixel scene(float coverage) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); glEnable(GL_DEPTH_TEST);
        glClearDepth(1); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(terrain); glUniform1f(glGetUniformLocation(terrain, DhPortalShaderCoverage.UNIFORM), coverage);
        // A distant green surface, then a near surface wholly outside DH's near plane.
        draw(0, 100, 0, 1, 0); draw(-1.2f, 1, 1, 0, 0);
        float[] measuredDepth = new float[1]; glReadPixels(4, 4, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, measuredDepth);
        glBindFramebuffer(GL_FRAMEBUFFER, 0); glDisable(GL_DEPTH_TEST); glUseProgram(composite);
        glUniform1f(glGetUniformLocation(composite, "clipZ"), 0);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, color);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, depth);
        glUniform1i(glGetUniformLocation(composite, "terrainColor"), 0);
        glUniform1i(glGetUniformLocation(composite, "terrainDepth"), 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] rgba = new float[4]; glReadPixels(4, 4, 1, 1, GL_RGBA, GL_FLOAT, rgba);
        return new Pixel(measuredDepth[0], rgba);
    }

    private void draw(float z, float distance, float red, float green, float blue) {
        glUniform1f(glGetUniformLocation(terrain, "clipZ"), z);
        glUniform1f(glGetUniformLocation(terrain, "distanceToCamera"), distance);
        glUniform3f(glGetUniformLocation(terrain, "surface"), red, green, blue); glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Test void zeroCoverageAndInheritedClampReproducePurpleSkyButDhScopePreservesDistantTerrain() {
        glEnable(GL_DEPTH_CLAMP);
        Pixel failed = scene(0);
        assertEquals(0, failed.depth(), 0.000001);
        assertArrayEquals(new float[]{.5f, 0, .75f, 1}, failed.rgba(), .006f);
        // The live timed coverage toggle hid this bug by discarding the near occluder.
        Pixel stock = scene(-1);
        assertEquals(.5f, stock.depth(), .000001);
        assertArrayEquals(new float[]{0, 1, 0, 1}, stock.rgba(), .006f);
        try (var scope = DhDepthClampScope.enter()) {
            assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
            Pixel corrected = scene(0);
            assertEquals(stock.depth(), corrected.depth(), .000001);
            assertArrayEquals(stock.rgba(), corrected.rgba(), .000001f);
        }
        assertTrue(glIsEnabled(GL_DEPTH_CLAMP), "vanilla portal terrain inherits its original clamp");
    }

    @Test void scopePreservesOrdinaryClippingWhenNoClampWasInherited() {
        Pixel before = scene(0);
        try (var scope = DhDepthClampScope.enter()) {
            assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
            Pixel inside = scene(0);
            assertEquals(before.depth(), inside.depth()); assertArrayEquals(before.rgba(), inside.rgba());
        }
        assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
    }

    @Test void nestedAndExceptionalScopesRestoreExactInheritedState() {
        for (boolean inherited : new boolean[]{false, true}) {
            if (inherited) glEnable(GL_DEPTH_CLAMP); else glDisable(GL_DEPTH_CLAMP);
            assertThrows(IllegalStateException.class, () -> {
                try (var outer = DhDepthClampScope.enter()) {
                    assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
                    try (var inner = DhDepthClampScope.enter()) {
                        assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
                        glEnable(GL_DEPTH_CLAMP); // Even an inner renderer's state change is restored.
                    }
                    assertFalse(glIsEnabled(GL_DEPTH_CLAMP));
                    throw new IllegalStateException("render failed");
                }
            });
            assertEquals(inherited, glIsEnabled(GL_DEPTH_CLAMP));
        }
    }
}
