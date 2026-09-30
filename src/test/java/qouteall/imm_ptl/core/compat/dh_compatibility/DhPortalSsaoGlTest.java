package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Exact installed DH apply shader, controlled depth discontinuity, real hidden GPU context. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhPortalSsaoGlTest {
    private static final int SIZE = 32;
    private static long window;
    private static String original;

    @BeforeAll static void init() throws Exception {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(SIZE, SIZE, "IP/Sable AO regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try (var stream = DhPortalSsaoGlTest.class.getResourceAsStream("/" + DhPortalSsao.APPLY_SHADER)) {
            assertNotNull(stream);
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @AfterAll static void close() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
    private static Matrix4f projection(boolean reverse) {
        return new Matrix4f().perspective((float)Math.toRadians(70), 1,
            reverse ? 4096 : .1f, reverse ? .1f : 4096, reverse);
    }
    private static int compile(int kind, String source) {
        int id = glCreateShader(kind);
        glShaderSource(id, source);
        glCompileShader(id);
        assertEquals(GL_TRUE, glGetShaderi(id, GL_COMPILE_STATUS), glGetShaderInfoLog(id));
        return id;
    }
    private static int texture(float[] values) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, SIZE, SIZE, 0, GL_RED, GL_FLOAT, values);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return id;
    }
    private static float[] draw(Matrix4f projection, boolean reverse, boolean patch, boolean portal) {
        float[] depth = new float[SIZE * SIZE], ao = new float[depth.length];
        Matrix4f base = projection(reverse);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            // A dark foreground ends at the middle; a well-lit background begins 20 blocks behind it.
            float distance = x < SIZE / 2 ? 20 : 40;
            float ndcX = (x + .5f) * 2 / SIZE - 1, ndcY = (y + .5f) * 2 / SIZE - 1;
            var clip = projection.transform(new Vector4f(ndcX * distance / base.m00(), ndcY * distance / base.m11(), -distance, 1));
            depth[y * SIZE + x] = reverse ? clip.z / clip.w : (clip.z / clip.w + 1) * .5f;
            ao[y * SIZE + x] = x < SIZE / 2 ? .2f : 1;
        }
        int vertex = compile(GL_VERTEX_SHADER, """
            #version 330 core
            out vec2 texCoord;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """);
        int fragment = compile(GL_FRAGMENT_SHADER, patch ? DhPortalSsao.patchApplyShader(original) : original);
        int program = glCreateProgram();
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        int vao = glGenVertexArrays(), fbo = glGenFramebuffers();
        glActiveTexture(GL_TEXTURE0);
        int depthTexture = texture(depth), aoTexture = texture(ao), output = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, output);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, SIZE, SIZE, 0, GL_RGBA, GL_FLOAT, (java.nio.FloatBuffer)null);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        try {
            glViewport(0, 0, SIZE, SIZE);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, depthTexture);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, aoTexture);
            glUniform1i(glGetUniformLocation(program, "uSourceDepthTexture"), 0);
            glUniform1i(glGetUniformLocation(program, "uSourceColorTexture"), 1);
            glUniform2f(glGetUniformLocation(program, "uViewSize"), SIZE, SIZE);
            glUniform1i(glGetUniformLocation(program, "uBlurRadius"), 2);
            glUniform1f(glGetUniformLocation(program, "uNearClipPlane"), .1f);
            glUniform1f(glGetUniformLocation(program, "uFarClipPlane"), 4096);
            glUniform1i(glGetUniformLocation(program, "uIsReverseZDepth"), reverse ? 1 : 0);
            if (patch) {
                glUniform1i(glGetUniformLocation(program, "uIpPortalProjection"), portal ? 1 : 0);
                glUniform1i(glGetUniformLocation(program, "uIpDepthZeroToOne"), reverse ? 1 : 0);
                glUniformMatrix4fv(glGetUniformLocation(program, "uIpInverseProjection"), false, new Matrix4f(projection).invert().get(new float[16]));
            }
            glDrawArrays(GL_TRIANGLES, 0, 3);
            float[] pixels = new float[SIZE * SIZE * 4];
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            assertEquals(GL_NO_ERROR, glGetError());
            return pixels;
        } finally {
            glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER, 0); glBindVertexArray(0);
            glDeleteTextures(depthTexture); glDeleteTextures(aoTexture); glDeleteTextures(output);
            glDeleteFramebuffers(fbo); glDeleteVertexArrays(vao);
            glDeleteProgram(program); glDeleteShader(vertex); glDeleteShader(fragment);
        }
    }

    @Test void obliqueBlurRetainsDepthEdgesInBothDepthDirections() {
        for (boolean reverse : new boolean[]{false, true}) {
            Matrix4f clipped = DhPortalProjection.clip(projection(reverse), new Matrix4f(), new Vector4f(.3f, .1f, -1, -.5f), reverse);
            assertNotNull(clipped);
            float[] control = draw(clipped, reverse, false, false);
            float[] repair = draw(clipped, reverse, true, true);
            float[] reference = draw(projection(reverse), reverse, true, true);
            int dark = (16 * SIZE + 15) * 4 + 3, light = (16 * SIZE + 16) * 4 + 3;
            assertTrue(control[dark] > .25f || control[light] < .95f, "Unpatched scalar reconstruction must reproduce depth-edge blur");
            assertEquals(.2f, repair[dark], .002f);
            assertEquals(1, repair[light], .002f);
            for (int i = 3; i < repair.length; i += 4) assertEquals(reference[i], repair[i], .002f);
        }
    }
    @Test void ordinaryViewRetainsExactInstalledShaderBehavior() {
        float[] control = draw(projection(false), false, false, false);
        float[] repair = draw(projection(false), false, true, false);
        assertArrayEquals(control, repair, .00001f);
    }
}
