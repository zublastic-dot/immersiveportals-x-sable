package qouteall.imm_ptl.core.render.impostor;

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL33.*;

/** Render-thread-only bounded images; never keeps destination depth or a reference to a world. */
public final class PortalImpostorGpu {
    private static final int MAX_MASK_PIXELS = 8_388_608;
    private static int rectifier, drawer, maskProgram, vao;
    private static int maskFbo, maskTexture, maskStencil, maskWidth, maskHeight;

    private PortalImpostorGpu() {}

    public static final class Frame implements AutoCloseable {
        private int texture;
        private final int size;
        private final double coverage;

        private Frame(int texture, int size, double coverage) {
            this.texture = texture; this.size = size; this.coverage = coverage;
        }
        public int textureId() { return texture; }
        public int width() { return size; }
        public int height() { return size; }
        public double coverageFraction() { return coverage; }
        public boolean isClosed() { return texture == 0; }
        @Override public void close() {
            if (texture != 0) { glDeleteTextures(texture); texture = 0; }
        }
    }

    /** Ref -1 is only for a dedicated destination image, never a shared source-world framebuffer. */
    public static Frame capture(PortalImpostorProjection projection, int colorTexture,
        int coverageFramebuffer, int stencilReference, int sourceWidth, int sourceHeight, int resolution) {
        if (projection == null || !projection.fullyVisible() || colorTexture <= 0
            || sourceWidth <= 0 || sourceHeight <= 0 || (long)sourceWidth * sourceHeight > MAX_MASK_PIXELS
            || resolution < 16 || resolution > 512 || stencilReference < -1 || stencilReference > 255)
            return null;
        // Do not nest an occlusion query belonging to another renderer/mod.
        if (glGetQueryi(GL_SAMPLES_PASSED, GL_CURRENT_QUERY) != 0
            || glGetQueryi(GL_ANY_SAMPLES_PASSED, GL_CURRENT_QUERY) != 0) return null;
        int texture = 0, framebuffer = 0, query = 0;
        boolean querying = false;
        try (State ignored = new State()) {
            initialize();
            neutralState();
            if (stencilReference >= 0) createCoverage(coverageFramebuffer, stencilReference, sourceWidth, sourceHeight);
            texture = texture(resolution, resolution, GL_RGBA8, GL_RGBA, GL_LINEAR);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            complete();
            glViewport(0, 0, resolution, resolution);
            glDisable(GL_STENCIL_TEST);
            glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0});
            glUseProgram(rectifier);
            uniformMatrix(rectifier, projection);
            glUniform1i(glGetUniformLocation(rectifier, "sourceColor"), 0);
            glUniform1i(glGetUniformLocation(rectifier, "sourceCoverage"), 1);
            glUniform1i(glGetUniformLocation(rectifier, "useCoverage"), stencilReference >= 0 ? 1 : 0);
            bindTexture(0, colorTexture);
            bindTexture(1, stencilReference >= 0 ? maskTexture : colorTexture);
            query = glGenQueries();
            glBeginQuery(GL_SAMPLES_PASSED, query); querying = true;
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glEndQuery(GL_SAMPLES_PASSED); querying = false;
            long samples = glGetQueryObjectui64(query, GL_QUERY_RESULT);
            if (samples == 0) return null;
            Frame frame = new Frame(texture, resolution, Math.min(1, samples / (double)(resolution * resolution)));
            texture = 0;
            return frame;
        } finally {
            if (querying) glEndQuery(GL_SAMPLES_PASSED);
            if (query != 0) glDeleteQueries(query);
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (texture != 0) glDeleteTextures(texture);
        }
    }

    public static boolean draw(Frame frame, PortalImpostorProjection projection, int targetFramebuffer,
        int targetWidth, int targetHeight, int stencilReference, float opacity) {
        if (frame == null || frame.isClosed() || projection == null || targetWidth <= 0 || targetHeight <= 0
            || !Float.isFinite(opacity) || opacity <= 0 || stencilReference < -1 || stencilReference > 255) return false;
        try (State ignored = new State()) {
            initialize(); neutralState();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer);
            glViewport(0, 0, targetWidth, targetHeight);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            if (stencilReference >= 0) {
                glEnable(GL_STENCIL_TEST); glStencilFunc(GL_EQUAL, stencilReference, 255);
                glStencilMask(0); glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            if (opacity < 1) {
                glEnable(GL_BLEND);
                glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
                glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            }
            glUseProgram(drawer); uniformMatrix(drawer, projection);
            glUniform1i(glGetUniformLocation(drawer, "image"), 0);
            glUniform1f(glGetUniformLocation(drawer, "opacity"), Math.min(1, opacity));
            bindTexture(0, frame.texture);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            return true;
        }
    }

    private static void createCoverage(int source, int reference, int width, int height) {
        if (maskFbo == 0 || width != maskWidth || height != maskHeight) {
            clearMask();
            maskTexture = texture(width, height, GL_R8, GL_RED, GL_NEAREST);
            maskStencil = glGenRenderbuffers(); glBindRenderbuffer(GL_RENDERBUFFER, maskStencil);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_STENCIL_INDEX8, width, height);
            maskFbo = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, maskFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, maskTexture, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, maskStencil);
            complete(); maskWidth = width; maskHeight = height;
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, maskFbo);
        glViewport(0, 0, width, height);
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0});
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_STENCIL_BUFFER_BIT, GL_NEAREST);
        glEnable(GL_STENCIL_TEST); glStencilFunc(GL_EQUAL, reference, 255);
        glStencilMask(0); glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glUseProgram(maskProgram); glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisable(GL_STENCIL_TEST);
    }

    private static int texture(int width, int height, int internal, int format, int filter) {
        int texture = glGenTextures();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_UNSIGNED_BYTE, (ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static void bindTexture(int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, texture); glBindSampler(unit, 0);
    }

    private static void uniformMatrix(int program, PortalImpostorProjection projection) {
        glUniformMatrix4fv(glGetUniformLocation(program, "clipFromUv"), false, projection.clipFromUv().get(new float[16]));
    }

    private static void neutralState() {
        glBindVertexArray(vao);
        for (int cap : State.CAPS) glDisable(cap);
        for (int i = 0; i < 8; i++) glDisable(GL_CLIP_DISTANCE0 + i);
        glColorMask(true, true, true, true); glDepthMask(false);
    }

    private static void complete() {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Portal impostor framebuffer is incomplete");
    }

    private static void initialize() {
        if (vao == 0) vao = glGenVertexArrays();
        if (rectifier == 0) rectifier = program("rectify.vert", "rectify.frag");
        if (drawer == 0) drawer = program("draw.vert", "draw.frag");
        if (maskProgram == 0) maskProgram = program("mask.vert", "mask.frag");
    }

    private static int program(String vertex, String fragment) {
        int v = 0, f = 0, p = 0;
        try {
            v = shader(GL_VERTEX_SHADER, vertex); f = shader(GL_FRAGMENT_SHADER, fragment);
            p = glCreateProgram(); glAttachShader(p, v); glAttachShader(p, f); glLinkProgram(p);
            if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(p));
            int result = p; p = 0; return result;
        } finally { if (v != 0) glDeleteShader(v); if (f != 0) glDeleteShader(f); if (p != 0) glDeleteProgram(p); }
    }

    private static int shader(int type, String name) {
        String path = "/assets/immersive_portals/shaders/impostor/" + name;
        try (var input = PortalImpostorGpu.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            int shader = glCreateShader(type);
            glShaderSource(shader, new String(input.readAllBytes(), StandardCharsets.UTF_8)); glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String error = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(error);
            }
            return shader;
        } catch (IOException failure) { throw new IllegalStateException(path, failure); }
    }

    private static void clearMask() {
        if (maskFbo != 0) glDeleteFramebuffers(maskFbo);
        if (maskTexture != 0) glDeleteTextures(maskTexture);
        if (maskStencil != 0) glDeleteRenderbuffers(maskStencil);
        maskFbo = maskTexture = maskStencil = maskWidth = maskHeight = 0;
    }

    /** Manager closes its Frames separately, on disconnect, resource reload, or renderer change. */
    public static void clear() {
        clearMask();
        if (rectifier != 0) glDeleteProgram(rectifier);
        if (drawer != 0) glDeleteProgram(drawer);
        if (maskProgram != 0) glDeleteProgram(maskProgram);
        if (vao != 0) glDeleteVertexArrays(vao);
        rectifier = drawer = maskProgram = vao = 0;
    }

    /** Raw GL changes are fully undone; Minecraft's cached render state is never modified. */
    private static final class State implements AutoCloseable {
        static final int[] CAPS = {GL_DEPTH_TEST, GL_STENCIL_TEST, GL_BLEND, GL_CULL_FACE, GL_SCISSOR_TEST,
            GL_FRAMEBUFFER_SRGB, GL_RASTERIZER_DISCARD, GL_POLYGON_OFFSET_FILL, GL_DEPTH_CLAMP, GL_DITHER};
        final boolean[] enabled = new boolean[CAPS.length], clip = new boolean[8];
        final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        final int program = glGetInteger(GL_CURRENT_PROGRAM), vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int active = glGetInteger(GL_ACTIVE_TEXTURE), renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
        final int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING), depthFunc = glGetInteger(GL_DEPTH_FUNC);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int[] viewport = new int[4], textures = new int[2], samplers = new int[2];
        final ByteBuffer colors = BufferUtils.createByteBuffer(4);
        final int[] front = stencil(false), back = stencil(true);
        final int blendSrc = glGetInteger(GL_BLEND_SRC_RGB), blendDst = glGetInteger(GL_BLEND_DST_RGB);
        final int blendSrcA = glGetInteger(GL_BLEND_SRC_ALPHA), blendDstA = glGetInteger(GL_BLEND_DST_ALPHA);
        final int blendEq = glGetInteger(GL_BLEND_EQUATION_RGB), blendEqA = glGetInteger(GL_BLEND_EQUATION_ALPHA);

        State() {
            glGetIntegerv(GL_VIEWPORT, viewport); glGetBooleanv(GL_COLOR_WRITEMASK, colors);
            for (int i = 0; i < CAPS.length; i++) enabled[i] = glIsEnabled(CAPS[i]);
            for (int i = 0; i < 8; i++) clip[i] = glIsEnabled(GL_CLIP_DISTANCE0 + i);
            for (int i = 0; i < 2; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D); samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
            }
            glActiveTexture(active);
        }
        private static int[] stencil(boolean back) {
            int[] keys = back ? new int[]{GL_STENCIL_BACK_FUNC, GL_STENCIL_BACK_REF, GL_STENCIL_BACK_VALUE_MASK,
                GL_STENCIL_BACK_WRITEMASK, GL_STENCIL_BACK_FAIL, GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL_STENCIL_BACK_PASS_DEPTH_PASS}
                : new int[]{GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK, GL_STENCIL_WRITEMASK,
                GL_STENCIL_FAIL, GL_STENCIL_PASS_DEPTH_FAIL, GL_STENCIL_PASS_DEPTH_PASS};
            int[] result = new int[7]; for (int i = 0; i < 7; i++) result[i] = glGetInteger(keys[i]); return result;
        }
        private static void restoreStencil(int side, int[] s) {
            glStencilFuncSeparate(side, s[0], s[1], s[2]); glStencilMaskSeparate(side, s[3]);
            glStencilOpSeparate(side, s[4], s[5], s[6]);
        }
        @Override public void close() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glUseProgram(program); glBindVertexArray(vertexArray);
            glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            for (int i = 0; i < 2; i++) {
                glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i]); glBindSampler(i, samplers[i]);
            }
            glActiveTexture(active); glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glDepthFunc(depthFunc); glDepthMask(depthMask);
            glColorMask(colors.get(0) != 0, colors.get(1) != 0, colors.get(2) != 0, colors.get(3) != 0);
            restoreStencil(GL_FRONT, front); restoreStencil(GL_BACK, back);
            glBlendEquationSeparate(blendEq, blendEqA); glBlendFuncSeparate(blendSrc, blendDst, blendSrcA, blendDstA);
            for (int i = 0; i < CAPS.length; i++) set(CAPS[i], enabled[i]);
            for (int i = 0; i < 8; i++) set(GL_CLIP_DISTANCE0 + i, clip[i]);
        }
        private static void set(int cap, boolean on) { if (on) glEnable(cap); else glDisable(cap); }
    }
}
