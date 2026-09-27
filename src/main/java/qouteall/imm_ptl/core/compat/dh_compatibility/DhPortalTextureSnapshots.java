package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL33.*;

/**
 * DH's no-shader vanilla fade reads its color/depth images again after IP has
 * rendered a portal. Preserve the parent's pixels, not just its framebuffer IDs.
 * Render-thread only; one reusable snapshot per active nested view.
 */
public final class DhPortalTextureSnapshots {
    private static final List<Slot> POOL = new ArrayList<>();
    private static int depth;
    private static boolean reportedTargetChange;

    private DhPortalTextureSnapshots() {}

    public static Scope capture(int color, int depthTexture) {
        if (color <= 0 || depthTexture <= 0 || !glIsTexture(color) || !glIsTexture(depthTexture)) return null;
        try (var state = new CopyState()) {
            TextureShape colorShape = TextureShape.read(color);
            TextureShape depthShape = TextureShape.read(depthTexture);
            if (colorShape.width <= 0 || colorShape.height <= 0
                || colorShape.width != depthShape.width || colorShape.height != depthShape.height) return null;
            if (depth == POOL.size()) POOL.add(new Slot());
            Slot slot = POOL.get(depth);
            slot.prepare(colorShape, depthShape);
            slot.attachSource(color, depthTexture);
            slot.copy(slot.sourceFramebuffer, slot.savedFramebuffer);
            return new Scope(depth++, slot, color, depthTexture);
        }
    }

    public static void clear() {
        if (depth != 0) throw new IllegalStateException("Cannot release active DH portal snapshots");
        for (Slot slot : POOL) slot.free();
        POOL.clear();
        reportedTargetChange = false;
    }

    public static final class Scope implements AutoCloseable {
        private final int index;
        private final Slot slot;
        private final int color, depthTexture;
        private boolean closed;

        private Scope(int index, Slot slot, int color, int depthTexture) {
            this.index = index;
            this.slot = slot;
            this.color = color;
            this.depthTexture = depthTexture;
        }

        @Override public void close() {
            if (closed) return;
            if (depth != index + 1) throw new IllegalStateException("DH portal snapshots must close in reverse order");
            try (var state = new CopyState()) {
                // Resource reload/resize must not copy into an unrelated or incompatible image.
                if (glIsTexture(color) && glIsTexture(depthTexture)
                    && slot.colorShape.equals(TextureShape.read(color))
                    && slot.depthShape.equals(TextureShape.read(depthTexture))) {
                    slot.attachSource(color, depthTexture);
                    slot.copy(slot.savedFramebuffer, slot.sourceFramebuffer);
                } else if (!reportedTargetChange) {
                    reportedTargetChange = true;
                    LogUtils.getLogger().warn("IP/Sable DH: targets changed during portal rendering; skipped stale image restore");
                }
            } finally {
                depth--;
                closed = true;
            }
        }
    }

    private record TextureShape(int width, int height, int format) {
        static TextureShape read(int texture) {
            glBindTexture(GL_TEXTURE_2D, texture);
            return new TextureShape(glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH),
                glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT),
                glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT));
        }
    }

    private static final class Slot {
        private final int sourceFramebuffer = glGenFramebuffers();
        private final int savedFramebuffer = glGenFramebuffers();
        private final int savedColor = glGenTextures();
        private final int savedDepth = glGenTextures();
        private TextureShape colorShape, depthShape;

        void prepare(TextureShape color, TextureShape depth) {
            if (color.equals(colorShape) && depth.equals(depthShape)) return;
            allocate(savedColor, color, false);
            allocate(savedDepth, depth, true);
            attach(savedFramebuffer, savedColor, savedDepth);
            colorShape = color;
            depthShape = depth;
        }

        void attachSource(int color, int depth) { attach(sourceFramebuffer, color, depth); }

        private static void allocate(int texture, TextureShape shape, boolean depth) {
            glBindTexture(GL_TEXTURE_2D, texture);
            int format = depth ? GL_DEPTH_COMPONENT : GL_RGBA;
            int type = GL_FLOAT;
            if (depth && (shape.format == GL_DEPTH24_STENCIL8 || shape.format == GL_DEPTH32F_STENCIL8)) {
                format = GL_DEPTH_STENCIL;
                type = shape.format == GL_DEPTH24_STENCIL8 ? GL_UNSIGNED_INT_24_8 : GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            }
            glTexImage2D(GL_TEXTURE_2D, 0, shape.format, shape.width, shape.height, 0, format, type, 0L);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        }

        private static void attach(int framebuffer, int color, int depth) {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete DH portal snapshot framebuffer");
        }

        void copy(int from, int to) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, from);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, to);
            glBlitFramebuffer(0, 0, colorShape.width, colorShape.height,
                0, 0, colorShape.width, colorShape.height, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        }

        void free() {
            glDeleteFramebuffers(sourceFramebuffer);
            glDeleteFramebuffers(savedFramebuffer);
            glDeleteTextures(savedColor);
            glDeleteTextures(savedDepth);
        }
    }

    /** These raw GL calls are temporary and restore MC's cached bindings unchanged. */
    private static final class CopyState implements AutoCloseable {
        private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        private final int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        private final int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        private final boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);

        CopyState() {
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_FRAMEBUFFER_SRGB);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        }

        @Override public void close() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            if (scissor) glEnable(GL_SCISSOR_TEST);
            if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
        }
    }
}
