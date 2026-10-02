package qouteall.imm_ptl.core.render.impostor;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Production shaders, coverage blit, rectification, composition and state isolation on a real GL context. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class PortalImpostorGpuGlTest {
    private static long window;
    private Target source, target;

    @BeforeAll static void context() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(64, 64, "Portal impostor regression", 0, 0);
        assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
    }

    @AfterAll static void end() {
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }

    @BeforeEach void setup() {
        source = new Target(); target = new Target();
        glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_BLEND);
        glDepthMask(true); glColorMask(true, true, true, true); glStencilMask(255);
        source.clear(1, 0, 0, 1, 1); target.clear(0, 0, 1, 1, 0);
    }

    @AfterEach void clean() {
        PortalImpostorGpu.clear(); source.close(); target.close();
        glUseProgram(0); glBindVertexArray(0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glBindSampler(0, 0); glBindSampler(1, 0); glActiveTexture(GL_TEXTURE0);
        for (int i = 0; i < 8; i++) glDisable(GL_CLIP_DISTANCE0 + i);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND);
        glDisable(GL_RASTERIZER_DISCARD); glDisable(GL_CULL_FACE); glDisable(GL_FRAMEBUFFER_SRGB);
        glColorMask(true, true, true, true); glDepthMask(true); glStencilMask(255);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static PortalImpostorProjection projection(double x, double width, double z) {
        return PortalImpostorProjection.create(new Matrix4f(), new Matrix4f(), new Vec3(x, 0, z),
            new Vec3(1, 0, 0), new Vec3(0, 1, 0), width, 2).orElseThrow();
    }

    private PortalImpostorGpu.Frame capture(int ref) {
        return PortalImpostorGpu.capture(projection(0, 2, 0), source.texture, source.fbo, ref, 64, 64, 64);
    }

    @Test void capturesCompletedDestinationAndReprojectsMovingAperture() {
        source.colorRect(32, 0, 32, 64, 0, 1, 0);
        try (var image = capture(1)) {
            assertNotNull(image); assertEquals(1, image.coverageFraction());
            assertTrue(PortalImpostorGpu.draw(image, projection(.25, 1, 0), target.fbo, 64, 64, -1, 1));
            assertArrayEquals(new int[]{255, 0, 0, 255}, target.pixel(27, 32));
            assertArrayEquals(new int[]{0, 255, 0, 255}, target.pixel(49, 32));
            assertArrayEquals(new int[]{0, 0, 255, 255}, target.pixel(5, 32));
        }
    }

    @Test void stencilCoverageDoesNotBakeSourceForegroundIntoImage() {
        source.stencilRect(0, 0, 32, 64, 0);
        source.colorRect(0, 0, 32, 64, 0, 1, 0);
        try (var image = capture(1)) {
            assertNotNull(image); assertEquals(.5, image.coverageFraction(), .001);
            PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, -1, 1);
            assertArrayEquals(new int[]{0, 0, 255, 255}, target.pixel(12, 32));
            assertArrayEquals(new int[]{255, 0, 0, 255}, target.pixel(48, 32));
        }
    }

    @Test void tiltedApertureRectifiesProjectiveGradientWithoutAnAffineSeam() {
        source.gradient();
        Matrix4f perspective = new Matrix4f().perspective(1, 1, .1f, 100);
        var tilted = PortalImpostorProjection.create(new Matrix4f(), perspective, new Vec3(0, 0, -3),
            new Vec3(.8, 0, .6), new Vec3(0, 1, 0), 2, 1.5).orElseThrow();
        assertTrue(tilted.fullyVisible());
        try (var image = PortalImpostorGpu.capture(tilted, source.texture, source.fbo, 1, 64, 64, 64)) {
            assertNotNull(image); assertEquals(1, image.coverageFraction());
            PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, -1, 1);
            for (int x : new int[]{7, 21, 38, 54}) for (int y : new int[]{9, 25, 42, 55}) {
                Vector4f clip = tilted.clipFromUv().transform(new Vector4f((x + .5f) / 64, (y + .5f) / 64, 0, 1));
                int sourceX = (int)((clip.x / clip.w * .5f + .5f) * 64);
                int sourceY = (int)((clip.y / clip.w * .5f + .5f) * 64);
                assertArrayEquals(new int[]{sourceX * 4, sourceY * 4, 0, 255}, target.pixel(x, y),
                    "homogeneous source sampling at " + x + "," + y);
            }
        }
    }

    @Test void linearSourceFilteringCannotMixMaskedForegroundAcrossBoundary() {
        source.colorRect(0, 0, 32, 64, 0, 1, 0);
        source.stencilRect(0, 0, 32, 64, 0);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // Offset a fractional source pixel so a LINEAR source sample would mix
        // green foreground into the first valid red texel to the right of x=32.
        var shifted = projection(.007, 1.98, 0);
        try (var image = PortalImpostorGpu.capture(shifted, source.texture, source.fbo, 1, 64, 64, 64)) {
            assertNotNull(image);
            int fbo = glGenFramebuffers();
            try {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, image.textureId(), 0);
                ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
                glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                for (int i = 0; i < 64 * 64; i++) if ((pixels.get(i * 4 + 3) & 255) == 255) {
                    assertEquals(255, pixels.get(i * 4) & 255);
                    assertEquals(0, pixels.get(i * 4 + 1) & 255);
                }
            } finally { glDeleteFramebuffers(fbo); }
        }
    }

    @Test void emptyCoverageIsRejectedAndDedicatedSourceDoesNotRequireStencil() {
        assertNull(capture(2));
        try (var image = capture(-1)) { assertNotNull(image); assertEquals(1, image.coverageFraction()); }
    }

    @Test void cachedPlaneObeysCurrentForegroundDepthAndWritesItsOwnDepth() {
        target.depthRect(0, 0, 32, 64, .25f);
        try (var image = capture(1)) {
            PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, -1, 1);
            assertArrayEquals(new int[]{0, 0, 255, 255}, target.pixel(12, 32));
            assertArrayEquals(new int[]{255, 0, 0, 255}, target.pixel(48, 32));
            assertEquals(.25f, target.depth(12, 32), .0001);
            assertEquals(.5f, target.depth(48, 32), .0001);
        }
    }

    @Test void targetStencilAndReentryOpacityAreRespected() {
        target.stencilRect(32, 0, 32, 64, 1);
        try (var image = capture(1)) {
            PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, 0, .5f);
            int[] blend = target.pixel(12, 32);
            assertEquals(128, blend[0], 1); assertEquals(128, blend[2], 1);
            assertArrayEquals(new int[]{0, 0, 255, 255}, target.pixel(48, 32));
        }
    }

    @Test void offscreenCaptureAndUnboundedAllocationAreRejected() {
        assertNull(PortalImpostorGpu.capture(projection(1, 2, 0), source.texture, source.fbo, 1, 64, 64, 64));
        assertNull(PortalImpostorGpu.capture(projection(0, 2, 0), source.texture, source.fbo, 1, 8192, 8192, 64));
        assertNull(PortalImpostorGpu.capture(projection(0, 2, 0), source.texture, source.fbo, 1, 64, 64, 1024));
    }

    @Test void existingOcclusionQueryIsNotNestedOrInterrupted() {
        for (int type : new int[]{GL_SAMPLES_PASSED, GL_ANY_SAMPLES_PASSED}) {
            int query = glGenQueries();
            try {
                glBeginQuery(type, query);
                try {
                    assertNull(capture(1));
                    assertEquals(query, glGetQueryi(type, GL_CURRENT_QUERY));
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally { glEndQuery(type); }
                assertEquals(0, glGetQueryObjectui(query, GL_QUERY_RESULT));
            } finally { glDeleteQueries(query); }
        }
    }

    @Test void frameResourcesHaveIdempotentOwnershipAndClosedFramesCannotDraw() {
        var image = capture(1); assertNotNull(image); int texture = image.textureId();
        assertTrue(glIsTexture(texture)); image.close(); image.close(); assertTrue(image.isClosed());
        assertFalse(glIsTexture(texture));
        assertFalse(PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, -1, 1));
    }

    @Test void captureAndDrawRestoreHostileCallerState() {
        int sampler = glGenSamplers(), unpack = glGenBuffers(), vertexArray = glGenVertexArrays();
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, source.fbo); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.fbo);
            glViewport(3, 5, 23, 29); glBindVertexArray(vertexArray);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, source.texture); glBindSampler(0, sampler);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, target.texture); glBindSampler(1, sampler);
            glActiveTexture(GL_TEXTURE3); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, 16, GL_STATIC_DRAW);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ZERO, GL_ONE);
            glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT, GL_MAX);
            glEnable(GL_SCISSOR_TEST); glScissor(4, 4, 1, 1); glEnable(GL_CULL_FACE);
            glEnable(GL_CLIP_DISTANCE0); glEnable(GL_RASTERIZER_DISCARD);
            glEnable(GL_STENCIL_TEST); glStencilFuncSeparate(GL_FRONT, GL_NOTEQUAL, 7, 23);
            glStencilFuncSeparate(GL_BACK, GL_GREATER, 9, 41);
            glStencilMaskSeparate(GL_FRONT, 17); glStencilMaskSeparate(GL_BACK, 19);
            glStencilOpSeparate(GL_FRONT, GL_INVERT, GL_INCR, GL_DECR);
            glStencilOpSeparate(GL_BACK, GL_ZERO, GL_REPLACE, GL_INCR_WRAP);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GREATER); glDepthMask(false);
            glColorMask(false, true, false, true);
            int[] before = state();
            try (var image = capture(1)) {
                assertNotNull(image); assertArrayEquals(before, state());
                PortalImpostorGpu.draw(image, projection(0, 2, 0), target.fbo, 64, 64, 0, .5f);
                assertArrayEquals(before, state());
            }
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); glDeleteBuffers(unpack);
            glBindVertexArray(0); glDeleteVertexArrays(vertexArray);
            glBindSampler(0, 0); glBindSampler(1, 0); glDeleteSamplers(sampler);
        }
    }

    private static int[] state() {
        int[] keys = {GL_READ_FRAMEBUFFER_BINDING, GL_DRAW_FRAMEBUFFER_BINDING, GL_VERTEX_ARRAY_BINDING,
            GL_CURRENT_PROGRAM, GL_ACTIVE_TEXTURE, GL_PIXEL_UNPACK_BUFFER_BINDING, GL_DEPTH_FUNC,
            GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK, GL_STENCIL_WRITEMASK, GL_STENCIL_FAIL,
            GL_STENCIL_PASS_DEPTH_FAIL, GL_STENCIL_PASS_DEPTH_PASS, GL_STENCIL_BACK_FUNC, GL_STENCIL_BACK_REF,
            GL_STENCIL_BACK_VALUE_MASK, GL_STENCIL_BACK_WRITEMASK, GL_STENCIL_BACK_FAIL,
            GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL_STENCIL_BACK_PASS_DEPTH_PASS, GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB,
            GL_BLEND_SRC_ALPHA, GL_BLEND_DST_ALPHA, GL_BLEND_EQUATION_RGB, GL_BLEND_EQUATION_ALPHA};
        int[] result = new int[keys.length + 4 + 4 + 4 + 9]; int cursor = 0;
        for (int key : keys) result[cursor++] = glGetInteger(key);
        int[] viewport = new int[4]; glGetIntegerv(GL_VIEWPORT, viewport);
        for (int v : viewport) result[cursor++] = v;
        ByteBuffer colors = BufferUtils.createByteBuffer(4); glGetBooleanv(GL_COLOR_WRITEMASK, colors);
        for (int i = 0; i < 4; i++) result[cursor++] = colors.get(i);
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < 2; i++) {
            glActiveTexture(GL_TEXTURE0 + i); result[cursor++] = glGetInteger(GL_TEXTURE_BINDING_2D);
            result[cursor++] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(active);
        for (int cap : new int[]{GL_BLEND, GL_SCISSOR_TEST, GL_CULL_FACE, GL_CLIP_DISTANCE0,
            GL_RASTERIZER_DISCARD, GL_STENCIL_TEST, GL_DEPTH_TEST, GL_FRAMEBUFFER_SRGB}) result[cursor++] = glIsEnabled(cap) ? 1 : 0;
        result[cursor] = glGetBoolean(GL_DEPTH_WRITEMASK) ? 1 : 0;
        return result;
    }

    private static class Target implements AutoCloseable {
        final int fbo = glGenFramebuffers(), texture = glGenTextures(), depth = glGenRenderbuffers();
        Target() {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glBindRenderbuffer(GL_RENDERBUFFER, depth); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 64, 64);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        }
        void clear(float r, float g, float b, float z, int stencil) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glClearBufferfv(GL_COLOR, 0, new float[]{r,g,b,1}); glClearBufferfi(GL_DEPTH_STENCIL, 0, z, stencil);
        }
        void colorRect(int x,int y,int w,int h,float r,float g,float b) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glEnable(GL_SCISSOR_TEST); glScissor(x,y,w,h);
            glClearBufferfv(GL_COLOR,0,new float[]{r,g,b,1}); glDisable(GL_SCISSOR_TEST);
        }
        void stencilRect(int x,int y,int w,int h,int value) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glEnable(GL_SCISSOR_TEST); glScissor(x,y,w,h);
            glClearBufferiv(GL_STENCIL,0,new int[]{value}); glDisable(GL_SCISSOR_TEST);
        }
        void depthRect(int x,int y,int w,int h,float value) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glEnable(GL_SCISSOR_TEST); glScissor(x,y,w,h);
            glClearBufferfv(GL_DEPTH,0,new float[]{value}); glDisable(GL_SCISSOR_TEST);
        }
        void gradient() {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
                pixels.put((byte)(x * 4)).put((byte)(y * 4)).put((byte)0).put((byte)255);
            pixels.flip(); glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        }
        int[] pixel(int x,int y) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo); ByteBuffer pixel=BufferUtils.createByteBuffer(4);
            glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            return new int[]{pixel.get(0)&255,pixel.get(1)&255,pixel.get(2)&255,pixel.get(3)&255};
        }
        float depth(int x,int y) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo); var pixel=BufferUtils.createFloatBuffer(1);
            glReadPixels(x,y,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,pixel); return pixel.get(0);
        }
        @Override public void close() { glDeleteFramebuffers(fbo); glDeleteTextures(texture); glDeleteRenderbuffers(depth); }
    }
}
