package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Pixel evidence for the real scope's selection routing; native callback linkage is checked separately. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class IrisPortalPipelineScopeGlTest {
    private record Target(int framebuffer, int texture) {}
    private static long window;
    private Target outer, child;
    private Backend backend;

    private static final class Backend implements IrisPortalPipelineScope.Selection<String, Target> {
        final Map<String, Target> targets;
        String dimension = "outer";
        Target selected;

        Backend(Target outer, Target child) {
            targets = Map.of("outer", outer, "child", child);
            selected = outer;
        }

        @Override public String dimension() { return dimension; }
        @Override public Target pipeline() { return selected; }
        @Override public Target prepare(String dimension) { return selected = targets.get(dimension); }
    }

    @BeforeAll static void createContext() {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(16, 16, "Iris pipeline scope pixel test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll static void destroyContext() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @BeforeEach void createIndependentTargets() {
        outer = createTarget();
        child = createTarget();
        assertNotEquals(outer.framebuffer(), child.framebuffer());
        assertNotEquals(outer.texture(), child.texture());
        backend = new Backend(outer, child);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_DITHER);
        glColorMask(true, true, true, true);
        paint(outer, 1, 0, 0);
        paint(child, 0, 1, 0);
    }

    @AfterEach void deleteOwnedTargets() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        for (Target target : new Target[] {outer, child}) {
            glDeleteTextures(target.texture());
            glDeleteFramebuffers(target.framebuffer());
        }
    }

    @Test void restoredWorldAloneLeavesCallbackWritingChildTarget() {
        backend.dimension = "child";
        backend.prepare("child");
        backend.dimension = "outer";
        paintSelected(0, 0, 1);

        assertPixel(outer, 1, 0, 0);
        assertPixel(child, 0, 0, 1);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test void productionScopeRoutesPostReturnCallbackToOuterTarget() {
        try (var scope = new IrisPortalPipelineScope<>(backend, () -> {})) {
            backend.dimension = "child";
            backend.prepare("child");
            paintSelected(0, 1, 0);
            backend.dimension = "outer";
        }
        paintSelected(0, 0, 1);

        assertPixel(outer, 0, 0, 1);
        assertPixel(child, 0, 1, 0);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test void productionScopeRoutesPostExceptionCallbackToOuterTarget() {
        var failure = new IllegalArgumentException("child draw failed");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> {
            try (var scope = new IrisPortalPipelineScope<>(backend, () -> {})) {
                try {
                    backend.dimension = "child";
                    backend.prepare("child");
                    paintSelected(0, 1, 0);
                    throw failure;
                } finally {
                    backend.dimension = "outer";
                }
            }
        }));
        paintSelected(0, 0, 1);

        assertPixel(outer, 0, 0, 1);
        assertPixel(child, 0, 1, 0);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static Target createTarget() {
        int framebuffer = glGenFramebuffers(), texture = glGenTextures();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        return new Target(framebuffer, texture);
    }

    // Mirrors the native callbacks' global-selection lookup, whose bytecode contract is tested separately.
    private void paintSelected(float r, float g, float b) { paint(backend.pipeline(), r, g, b); }

    private static void paint(Target target, float r, float g, float b) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer());
        glClearColor(r, g, b, 1);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void assertPixel(Target target, float r, float g, float b) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer());
        float[] pixel = new float[4];
        glReadPixels(2, 2, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        assertArrayEquals(new float[] {r, g, b, 1}, pixel, .001f);
    }
}
