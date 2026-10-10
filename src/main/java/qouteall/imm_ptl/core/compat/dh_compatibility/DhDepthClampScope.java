package qouteall.imm_ptl.core.compat.dh_compatibility;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP;

/** DH must clip at its own near plane, even inside a depth-clamped portal terrain layer. */
public final class DhDepthClampScope implements AutoCloseable {
    private final boolean inherited = glIsEnabled(GL_DEPTH_CLAMP);
    private boolean closed;

    private DhDepthClampScope() { glDisable(GL_DEPTH_CLAMP); }

    public static DhDepthClampScope enter() { return new DhDepthClampScope(); }

    @Override public void close() {
        if (closed) return;
        if (inherited) glEnable(GL_DEPTH_CLAMP);
        else glDisable(GL_DEPTH_CLAMP);
        closed = true;
    }
}
