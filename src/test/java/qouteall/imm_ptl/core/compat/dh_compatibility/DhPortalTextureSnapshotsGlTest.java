package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Real GPU pixel regressions. Run with -PdhGlTests=true (a hidden window only). */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhPortalTextureSnapshotsGlTest {
    private static long window;
    private final List<Target> targets = new ArrayList<>();

    @BeforeAll static void openContext() {
        assertTrue(glfwInit(), "GLFW initialization");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "IP/Sable DH pixel regression", 0, 0);
        assertNotEquals(0, window, "Hidden OpenGL context");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("DH pixel regression GPU: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
    }

    @AfterAll static void closeContext() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @BeforeEach void resetState() {
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);
        glColorMask(true, true, true, true);
        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }

    @AfterEach void cleanTargets() {
        DhPortalTextureSnapshots.clear();
        for (Target t : targets) t.free();
        assertEquals(GL_NO_ERROR, glGetError(), "Snapshot operations must not produce OpenGL errors");
    }

    private Target target(int size) {
        var target = new Target(size);
        targets.add(target);
        return target;
    }

    @Test void parentPixelsReturnAfterPortalAndSiblingViews() {
        Target shared = target(8);
        shared.fill(1, 0, 0, .25);
        for (int sibling = 0; sibling < 4; sibling++) {
            try (var scope = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
                assertNotNull(scope);
                shared.fill(0, 1, 0, .75);
                shared.assertPixels(0, 1, 0, .75);
            }
            shared.assertPixels(1, 0, 0, .25);
        }
    }

    @Test void nestedPortalsRestoreTheirImmediateParentInReverseOrder() {
        Target shared = target(8);
        shared.fill(1, 0, 0, .25);
        try (var first = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
            shared.fill(0, 1, 0, .5);
            try (var second = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
                shared.fill(0, 0, 1, .75);
            }
            shared.assertPixels(0, 1, 0, .5);
        }
        shared.assertPixels(1, 0, 0, .25);
    }

    @Test void renderingExceptionStillRestoresBothImages() {
        Target shared = target(8);
        shared.fill(1, 0, 0, .25);
        RuntimeException error = new RuntimeException("nested renderer failed");
        assertSame(error, assertThrows(RuntimeException.class, () -> {
            try (var scope = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
                shared.fill(0, 1, 0, .75);
                throw error;
            }
        }));
        shared.assertPixels(1, 0, 0, .25);
    }

    @Test void snapshotsCopyAllPixelsAndPreserveFramebufferTextureAndMaskState() {
        Target shared = target(8), read = target(8), draw = target(8);
        shared.fill(1, 0, 0, .25);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read.fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw.fbo);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, draw.color);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, 1, 1);
        glEnable(GL_FRAMEBUFFER_SRGB);
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, 3, 0x7F);
        glStencilMask(0x3F);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        int pbo = glGenBuffers();
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
        var scope = DhPortalTextureSnapshots.capture(shared.color, shared.depth);
        assertBindings(read, draw, pbo);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        shared.fill(0, 1, 0, .75);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read.fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw.fbo);
        glEnable(GL_SCISSOR_TEST);
        glEnable(GL_FRAMEBUFFER_SRGB);
        scope.close();
        assertBindings(read, draw, pbo);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glDeleteBuffers(pbo);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        shared.assertPixels(1, 0, 0, .25);
    }

    private void assertBindings(Target read, Target draw, int pbo) {
        assertEquals(read.fbo, glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
        assertEquals(draw.fbo, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertEquals(GL_TEXTURE3, glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(draw.color, glGetInteger(GL_TEXTURE_BINDING_2D));
        assertEquals(pbo, glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING));
        assertTrue(glIsEnabled(GL_SCISSOR_TEST));
        assertTrue(glIsEnabled(GL_FRAMEBUFFER_SRGB));
        assertTrue(glIsEnabled(GL_STENCIL_TEST));
        assertEquals(GL_EQUAL, glGetInteger(GL_STENCIL_FUNC));
        assertEquals(3, glGetInteger(GL_STENCIL_REF));
        assertEquals(0x3F, glGetInteger(GL_STENCIL_WRITEMASK));
        assertEquals(GL_INCR, glGetInteger(GL_STENCIL_PASS_DEPTH_PASS));
    }

    @Test void poolAdaptsToResizeAndCanBeReleasedAndRecreated() {
        for (int size : new int[]{8, 16, 4}) {
            Target shared = target(size);
            shared.fill(1, 0, 0, .25);
            try (var scope = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
                shared.fill(0, 1, 0, .75);
            }
            shared.assertPixels(1, 0, 0, .25);
        }
        DhPortalTextureSnapshots.clear();
        Target fresh = target(8);
        fresh.fill(0, 0, 1, .75);
        try (var scope = DhPortalTextureSnapshots.capture(fresh.color, fresh.depth)) {
            fresh.fill(1, 0, 0, .25);
        }
        fresh.assertPixels(0, 0, 1, .75);
    }

    @Test void uninitializedDhTargetsDoNotAllocateAScope() {
        assertNull(DhPortalTextureSnapshots.capture(-1, -1));
        assertNull(DhPortalTextureSnapshots.capture(0, 0));
    }

    @Test void actualDhLateFadeReproducesLeakWithoutRestoreAndUsesParentAfterRestore() throws IOException {
        Target shared = target(8), combined = target(8), output = target(8);
        combined.fill(.25f, .125f, 0, .5);
        shared.fill(1, 0, 0, .25);
        int program = fadeProgram();
        int vao = glGenVertexArrays();
        try {
            try (var scope = DhPortalTextureSnapshots.capture(shared.color, shared.depth)) {
                // The nested portal replaces DH's shared image with the other dimension.
                shared.fill(0, 1, 0, .75);
                runFade(program, vao, combined, shared, output);
                output.assertColor(0, 1, 0); // control: wrong world's pixels reach the outer image
            }
            runFade(program, vao, combined, shared, output);
            output.assertColor(1, 0, 0); // repair: the same DH shader reads the parent's pixels
        } finally {
            glUseProgram(0);
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
        }
    }

    private static int fadeProgram() throws IOException {
        String fragment;
        try (var in = DhPortalTextureSnapshotsGlTest.class.getResourceAsStream("/assets/distanthorizons/shaders/fade/gl/vanilla_fade.frag")) {
            assertNotNull(in);
            fragment = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int vertex = shader(GL_VERTEX_SHADER, """
            #version 330 core
            out vec2 texCoord;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """);
        int frag = shader(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, frag);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        glDeleteShader(vertex);
        glDeleteShader(frag);
        return program;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }

    private static void runFade(int program, int vao, Target combined, Target shared, Target output) {
        glBindFramebuffer(GL_FRAMEBUFFER, output.fbo);
        glViewport(0, 0, output.size, output.size);
        glUseProgram(program);
        int[] textures = {combined.depth, shared.depth, combined.color, shared.color};
        String[] names = {"uMcDepthTexture", "uDhDepthTexture", "uCombinedMcDhColorTexture", "uDhColorTexture"};
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glUniform1i(glGetUniformLocation(program, names[i]), i);
        }
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        glUniformMatrix4fv(glGetUniformLocation(program, "uMcInvMvmProj"), false, identity);
        glUniformMatrix4fv(glGetUniformLocation(program, "uDhInvMvmProj"), false, identity);
        glUniform1f(glGetUniformLocation(program, "uStartFadeBlockDistance"), -1);
        glUniform1f(glGetUniformLocation(program, "uEndFadeBlockDistance"), 0);
        glUniform1f(glGetUniformLocation(program, "uMaxLevelHeight"), 1024);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static final class Target {
        final int size, fbo = glGenFramebuffers(), color = glGenTextures(), depth = glGenTextures();
        Target(int size) {
            this.size = size;
            glBindTexture(GL_TEXTURE_2D, color);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        }
        void fill(float r, float g, float b, double z) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glClearColor(r, g, b, 1);
            glClearDepth(z);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        }
        void assertColor(float r, float g, float b) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            float[] pixels = new float[size * size * 4];
            glReadPixels(0, 0, size, size, GL_RGBA, GL_FLOAT, pixels);
            for (int i = 0; i < pixels.length; i += 4) {
                assertEquals(r, pixels[i], .005);
                assertEquals(g, pixels[i + 1], .005);
                assertEquals(b, pixels[i + 2], .005);
            }
        }
        void assertPixels(float r, float g, float b, double z) {
            assertColor(r, g, b);
            float[] pixels = new float[size * size];
            glReadPixels(0, 0, size, size, GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
            for (float pixel : pixels) assertEquals(z, pixel, .00001);
        }
        void free() {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(color);
            glDeleteTextures(depth);
        }
    }
}
