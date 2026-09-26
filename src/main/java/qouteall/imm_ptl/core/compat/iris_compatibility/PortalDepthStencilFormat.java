package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.function.IntConsumer;

import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;

/** Adapts only IP-owned scratch storage; never reallocates the rendered source. */
final class PortalDepthStencilFormat {
    private PortalDepthStencilFormat() {}

    static boolean matchPackedSource(int sourceFormat, int destinationFormat, IntConsumer reallocate) {
        // Preserve the existing depth-only renderer path. A packed source, however,
        // must be copied to the same packed format (D32FS8 and D24S8 are not compatible).
        if (sourceFormat != GL_DEPTH24_STENCIL8 && sourceFormat != GL_DEPTH32F_STENCIL8) {
            return false;
        }
        if (sourceFormat == destinationFormat) {
            return false;
        }
        reallocate.accept(sourceFormat);
        return true;
    }
}
