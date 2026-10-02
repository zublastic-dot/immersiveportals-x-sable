package qouteall.imm_ptl.core.lighting;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/** GL 3.3 native-size depth copy. No readback, texture resampling, or source sampler mutation. */
final class PortalSourceShadowDepth implements AutoCloseable {
    record Spec(int resolution, int internalFormat, int format, int type, int depthBits, long bytes) {}
    record Inspection(@Nullable Spec spec, @Nullable String rejection) {}
    private int texture;
    private final Spec spec;
    private String copyRejection;

    private PortalSourceShadowDepth(int texture, Spec spec) { this.texture = texture; this.spec = spec; }
    int texture() { return texture; }
    int resolution() { return spec.resolution; }
    long bytes() { return spec.bytes; }
    boolean matches(Spec other) { return spec.equals(other); }
    String description() { return "internal=" + spec.internalFormat + ", depthBits=" + spec.depthBits; }
    String copyRejection() { return copyRejection; }

    static Inspection inspect(int source, int resolution) {
        if (resolution < 1 || resolution > PortalSourceShadow.MAX_RESOLUTION)
            return new Inspection(null, "resolution-out-of-range: " + resolution);
        if (!glIsTexture(source)) return new Inspection(null, "not-a-texture: " + source);
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, source);
            int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (width != resolution || height != resolution)
                return new Inspection(null, "native-size-mismatch: " + width + "x" + height + ", expected=" + resolution);
            int internal = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            int depthBits = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_SIZE);
            int format = GL_DEPTH_COMPONENT, type = GL_FLOAT, bytesPerPixel = 4;
            switch (internal) {
                // Iris ShadowRenderTargets uses DepthBufferFormat.DEPTH. Drivers may
                // report its unsized enum even when the backing depth image is 32-bit.
                // Match DepthTexture.resize's allocation type instead of forcing float
                // or a guessed sized format; depth blits require matching precision.
                case GL_DEPTH_COMPONENT -> type = GL_UNSIGNED_SHORT;
                case GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F -> {}
                case GL_DEPTH_STENCIL, GL_DEPTH24_STENCIL8 -> { format = GL_DEPTH_STENCIL; type = GL_UNSIGNED_INT_24_8; }
                case GL_DEPTH32F_STENCIL8 -> { format = GL_DEPTH_STENCIL; type = GL_FLOAT_32_UNSIGNED_INT_24_8_REV; bytesPerPixel = 8; }
                default -> { return new Inspection(null, "unsupported-depth-format: internal=" + internal + ", depthBits=" + depthBits); }
            }
            if (depthBits < 1 || depthBits > 32)
                return new Inspection(null, "unsupported-depth-precision: internal=" + internal + ", depthBits=" + depthBits);
            // Budget depth16/24 conservatively as four bytes; driver padding is not free.
            return new Inspection(new Spec(resolution, internal, format, type, depthBits,
                (long) resolution * resolution * bytesPerPixel), null);
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
            int allocatedBits = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_SIZE);
            if (allocatedBits != spec.depthBits)
                throw new IllegalStateException("Owned shadow depth precision mismatch: native=" + spec.depthBits
                    + ", allocated=" + allocatedBits + ", internal=" + spec.internalFormat);
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
        copyRejection = null;
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
            int sourceStatus = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
            int targetStatus = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            if (sourceStatus != GL_FRAMEBUFFER_COMPLETE || targetStatus != GL_FRAMEBUFFER_COMPLETE) {
                copyRejection = "incomplete-depth-fbo: source=" + sourceStatus + ", target=" + targetStatus;
                return false;
            }
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
