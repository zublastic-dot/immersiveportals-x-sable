package ipl.sable.render;

import static org.lwjgl.opengl.GL33C.*;

/** Materialize freshly generated names before Veil labels them or uses DSA. */
public final class GlObjectLifecycle {
    private GlObjectLifecycle() {}

    public static int initializeGeneratedBuffer(int name) {
        int previous = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        glBindBuffer(GL_ARRAY_BUFFER, name);
        glBindBuffer(GL_ARRAY_BUFFER, previous);
        return name;
    }

    public static int initializeGeneratedVertexArray(int name) {
        int previous = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        glBindVertexArray(name);
        glBindVertexArray(previous);
        return name;
    }
}
