package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import static org.lwjgl.opengl.GL33.*;

/** Runs the installed DH shaders against private portal histories. No DH singleton is modified. */
public final class DhPortalTaaPipeline implements AutoCloseable {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_VIEWS = 8;
    private final LinkedHashMap<Object, View> views = new LinkedHashMap<>(16, .75f, true);
    private int taa, sharpen, vao;

    public View prepare(Object key, int frame, long now, int width, int height,
                        Matrix4f projection, Matrix4f modelView, Vector3d camera, boolean zeroToOne) {
        if (width <= 0 || height <= 0 || (long)width * height * 8 > MAX_BYTES) return null;
        try (var state = new State()) {
            prune(frame, now);
            View view = views.get(key);
            if (view != null && (view.width != width || view.height != height || view.zeroToOne != zeroToOne)) {
                views.remove(key); view.close(); view = null;
            }
            if (view == null) {
                long bytes = (long)width * height * 8;
                while (!views.isEmpty() && (views.size() >= MAX_VIEWS || allocatedBytes() + bytes > MAX_BYTES)) {
                    var oldest = views.entrySet().iterator(); var victim = oldest.next();
                    // Never evict a history that may still be used by a nested scope this frame.
                    if (victim.getValue().lastFrame == frame) return null;
                    victim.getValue().close(); oldest.remove();
                }
                view = new View(width, height, zeroToOne); views.put(key, view);
            }
            if (view.history.begin(frame, now, projection, modelView, camera)) view.readIndex ^= 1;
            view.lastFrame = frame; view.lastSeen = now;
            return view;
        }
    }

    public void prune(int frame, long now) {
        var it = views.values().iterator();
        while (it.hasNext()) {
            var view = it.next();
            if (view.lastFrame != frame && (now - view.lastSeen > 2_000_000_000L || now < view.lastSeen)) {
                view.close(); it.remove();
            }
        }
    }
    public long allocatedBytes() { return views.values().stream().mapToLong(v -> (long)v.width * v.height * 8).sum(); }
    public int viewCount() { return views.size(); }

    /** Copy only on crossing, before DH can overwrite its departing main-view ping-pong targets. */
    public boolean capture(Object key, DhTaaHistory.Snapshot snapshot, int sourceFramebuffer,
                           int width, int height, boolean zeroToOne) {
        try (var state = new State()) {
            if (snapshot == null || snapshot.phase() < 0 || !glIsFramebuffer(sourceFramebuffer)) return false;
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFramebuffer);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false;
            var view = prepare(key, snapshot.frame(), snapshot.time(), width, height,
                snapshot.projection(), snapshot.view(), snapshot.camera(), zeroToOne);
            if (view == null) return false;
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, view.fbos[view.readIndex ^ 1]);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            view.history.restoreCompleted(snapshot);
            return true;
        }
    }

    public record Transfer(View donor, DhTaaHistory.Snapshot snapshot) {}
    public Transfer transfer(Object key, int frame, long now, int width, int height,
                             Matrix4f projection, Matrix4f modelView, Vector3d camera, boolean zeroToOne) {
        View donor = views.get(key);
        if (donor == null || donor.width != width || donor.height != height || donor.zeroToOne != zeroToOne) return null;
        var snapshot = donor.history.snapshot();
        return snapshot != null && snapshot.matches(frame, now, projection, modelView, camera)
            ? new Transfer(donor, snapshot) : null;
    }

    /** Copies unsharpened accumulated color, preserving all caller GL state. Null/reused donors clear stale main history. */
    public static boolean seed(Transfer transfer, int destination, int width, int height) {
        try (var state = new State()) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination);
            if (transfer == null || transfer.donor.history.snapshot() != transfer.snapshot
                || transfer.donor.width != width || transfer.donor.height != height) {
                glClearBufferfv(GL_COLOR, 0, new float[4]); return false;
            }
            var donor = transfer.donor;
            glBindFramebuffer(GL_READ_FRAMEBUFFER, donor.fbos[donor.readIndex ^ 1]);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            return true;
        }
    }

    public void render(View view, int currentColor, int depth, int destination,
                       Matrix4f projection, Matrix4f modelView, Vector3d camera) {
        if (view == null) return;
        try (var state = new State()) {
            init();
            glViewport(0, 0, view.width, view.height);
            glBindVertexArray(vao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, view.fbos[view.readIndex]);
            if (!view.history.valid) glClearBufferfv(GL_COLOR, 0, new float[4]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, view.fbos[view.readIndex ^ 1]);
            glUseProgram(taa);
            matrix(taa, "uDhProjectionInverse", new Matrix4f(projection).invert());
            matrix(taa, "uDhModelViewInverse", new Matrix4f(modelView).invert());
            matrix(taa, "uDhPrevProjMvm", view.history.previousCombined);
            scalar(taa, "uCameraOffsetX", (float)(camera.x - view.history.previousCamera.x));
            scalar(taa, "uCameraOffsetY", (float)(camera.y - view.history.previousCamera.y));
            scalar(taa, "uCameraOffsetZ", (float)(camera.z - view.history.previousCamera.z));
            scalar(taa, "uViewWidth", view.width); scalar(taa, "uViewHeight", view.height);
            glUniform1i(glGetUniformLocation(taa, "uDepthIsZeroToPositiveOne"), view.zeroToOne ? 1 : 0);
            texture(taa, "uCurrentColorSampler", 0, currentColor);
            texture(taa, "uCurrentDepthSampler", 1, depth);
            texture(taa, "uHistoryColorSampler", 2, view.textures[view.readIndex]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination);
            glUseProgram(sharpen);
            scalar(sharpen, "uViewWidth", view.width); scalar(sharpen, "uViewHeight", view.height);
            scalar(sharpen, "uCasAmount", .3f);
            texture(sharpen, "uCurrentColorSampler", 0, view.textures[view.readIndex ^ 1]);
            // Match DH's sharpen composite, including its alpha policy.
            glEnable(GL_BLEND);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            if (state.enabled[2]) glEnable(GL_STENCIL_TEST);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            view.history.complete();
        } catch (RuntimeException | Error failure) {
            view.history.invalidate();
            throw failure;
        }
    }

    private void init() {
        if (taa != 0) return;
        int newTaa = 0, newSharpen = 0;
        try {
            newTaa = program("taa.frag"); newSharpen = program("sharpen.frag");
            vao = glGenVertexArrays(); taa = newTaa; sharpen = newSharpen;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(newTaa); glDeleteProgram(newSharpen); throw failure;
        }
    }
    private static int program(String fragment) {
        String vertex = "#version 330 core\nout vec2 texCoord; void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);texCoord=p;gl_Position=vec4(p*2.0-1.0,0,1);}";
        String source = loadShader(fragment);
        int vs = 0, fs = 0, program = 0;
        try {
            vs = compile(GL_VERTEX_SHADER, vertex); fs = compile(GL_FRAGMENT_SHADER, source);
            program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error e) { glDeleteProgram(program); throw e; }
        finally { glDeleteShader(vs); glDeleteShader(fs); }
    }
    static String loadShader(String fragment) {
        // Match DH's owning-loader lookup. Class.getResourceAsStream on our mod
        // cannot see assets in DH's separate named module under NeoForge.
        var loader = com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader.class.getClassLoader();
        try (var in = loader.getResourceAsStream("assets/distanthorizons/shaders/antialias/gl/" + fragment)) {
            if (in == null) throw new IOException("Missing installed DH shader " + fragment);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(log);
        }
        return shader;
    }
    private static void matrix(int program, String name, Matrix4f value) { glUniformMatrix4fv(glGetUniformLocation(program, name), false, value.get(new float[16])); }
    private static void scalar(int program, String name, float value) { glUniform1f(glGetUniformLocation(program, name), value); }
    private static void texture(int program, String name, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, texture);
        glBindSampler(unit, 0); glUniform1i(glGetUniformLocation(program, name), unit);
    }
    @Override public void close() {
        for (var view : views.values()) view.close(); views.clear();
        glDeleteProgram(taa); glDeleteProgram(sharpen); glDeleteVertexArrays(vao);
        taa = sharpen = vao = 0;
    }

    public static final class View implements AutoCloseable {
        public final DhTaaHistory history = new DhTaaHistory();
        final int width, height;
        final boolean zeroToOne;
        final int[] textures = new int[2], fbos = new int[2];
        private int readIndex, lastFrame;
        private long lastSeen;
        private View(int width, int height, boolean zeroToOne) {
            this.width = width; this.height = height; this.zeroToOne = zeroToOne;
            try {
                for (int i = 0; i < 2; i++) {
                    textures[i] = glGenTextures(); fbos[i] = glGenFramebuffers();
                    glBindTexture(GL_TEXTURE_2D, textures[i]);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB10_A2, width, height, 0, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, 0L);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0);
                    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                        throw new IllegalStateException("Incomplete portal TAA framebuffer");
                }
            } catch (RuntimeException | Error e) { close(); throw e; }
        }
        @Override public void close() {
            for (int i = 0; i < 2; i++) {
                glDeleteTextures(textures[i]); glDeleteFramebuffers(fbos[i]); textures[i] = fbos[i] = 0;
            }
            history.invalidate();
        }
    }

    /** Raw calls restore bindings and enables exactly, leaving Minecraft's GL cache untouched. */
    private static final class State implements AutoCloseable {
        private static final int[] CAPS = {GL_DEPTH_TEST, GL_BLEND, GL_STENCIL_TEST, GL_SCISSOR_TEST, GL_CULL_FACE, GL_FRAMEBUFFER_SRGB, GL_CLIP_DISTANCE0};
        final boolean[] enabled = new boolean[CAPS.length];
        private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        private final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        private final int active = glGetInteger(GL_ACTIVE_TEXTURE), unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int[] textures = new int[3], samplers = new int[3], viewport = new int[4];
        private final byte[] mask = new byte[4];
        private final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        private final int eqRgb = glGetInteger(GL_BLEND_EQUATION_RGB), eqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        State() {
            for (int i = 0; i < CAPS.length; i++) { enabled[i] = glIsEnabled(CAPS[i]); glDisable(CAPS[i]); }
            glGetIntegerv(GL_VIEWPORT, viewport);
            var buffer = org.lwjgl.BufferUtils.createByteBuffer(4); glGetBooleanv(GL_COLOR_WRITEMASK, buffer); buffer.get(mask);
            glColorMask(true, true, true, true); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < 3; i++) {
                glActiveTexture(GL_TEXTURE0 + i); textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D); samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
            }
        }
        @Override public void close() {
            for (int i = 0; i < 3; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i]); glBindSampler(i, samplers[i]); }
            glActiveTexture(active); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glUseProgram(program); glBindVertexArray(vao); glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glColorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
            glBlendEquationSeparate(eqRgb, eqAlpha); glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            for (int i = 0; i < CAPS.length; i++) { if (enabled[i]) glEnable(CAPS[i]); else glDisable(CAPS[i]); }
        }
    }
}
