package qouteall.imm_ptl.core.lighting;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/** GL 3.3 native-size depth copy. No readback, texture resampling, or source sampler mutation. */
final class PortalSourceShadowDepth implements AutoCloseable {
    record Spec(int resolution, int internalFormat, int format, int type, long bytes) {}
    private int texture;
    private final Spec spec;

    private PortalSourceShadowDepth(int texture, Spec spec) { this.texture = texture; this.spec = spec; }
    int texture() { return texture; }
    int resolution() { return spec.resolution; }
    long bytes() { return spec.bytes; }
    boolean matches(Spec other) { return spec.equals(other); }

    static @Nullable Spec inspect(int source, int resolution) {
        if (resolution < 1 || resolution > PortalSourceShadow.MAX_RESOLUTION || !glIsTexture(source)) return null;
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, source);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != resolution
                || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != resolution) return null;
            int internal = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            int format = GL_DEPTH_COMPONENT, type = GL_FLOAT, bytesPerPixel = 4;
            switch (internal) {
                case GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F -> {}
                case GL_DEPTH24_STENCIL8 -> { format = GL_DEPTH_STENCIL; type = GL_UNSIGNED_INT_24_8; }
                case GL_DEPTH32F_STENCIL8 -> { format = GL_DEPTH_STENCIL; type = GL_FLOAT_32_UNSIGNED_INT_24_8_REV; bytesPerPixel = 8; }
                default -> { return null; }
            }
            // Budget depth16/24 conservatively as four bytes; driver padding is not free.
            return new Spec(resolution, internal, format, type, (long) resolution * resolution * bytesPerPixel);
        } finally { glBindTexture(GL_TEXTURE_2D, previous); }
    }

    static PortalSourceShadowDepth allocate(Spec spec) {
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int texture = 0;
        try {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glTexImage2D(GL_TEXTURE_2D, 0, spec.internalFormat, spec.resolution, spec.resolution, 0,
                spec.format, spec.type, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
            PortalSourceShadowDepth result = new PortalSourceShadowDepth(texture, spec);
            texture = 0;
            return result;
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            if (texture != 0) glDeleteTextures(texture);
        }
    }

    boolean copyFrom(int source) {
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        int sourceFbo = 0, targetFbo = 0;
        try {
            sourceFbo = glGenFramebuffers(); targetFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, sourceFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, source, 0);
            glReadBuffer(GL_NONE); glDrawBuffer(GL_NONE);
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            glReadBuffer(GL_NONE); glDrawBuffer(GL_NONE);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFbo);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE
                || glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false;
            glDisable(GL_SCISSOR_TEST);
            glBlitFramebuffer(0, 0, spec.resolution, spec.resolution, 0, 0, spec.resolution, spec.resolution,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST);
            return true;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (sourceFbo != 0) glDeleteFramebuffers(sourceFbo);
            if (targetFbo != 0) glDeleteFramebuffers(targetFbo);
        }
    }

    @Override public void close() { if (texture != 0) { glDeleteTextures(texture); texture = 0; } }
}
