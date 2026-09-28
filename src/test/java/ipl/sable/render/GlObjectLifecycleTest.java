package ipl.sable.render;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Reproduces the actual driver failures, then tests the production allocation fix. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class GlObjectLifecycleTest {
    private static long window;

    @BeforeAll static void open() {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "IP/Sable GL lifecycle regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll static void close() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test void generatedBufferCanBeLabeledAndUploadedBeforeVanillaFirstBind() {
        int buffer = glGenBuffers();
        try {
            assertFalse(glIsBuffer(buffer));
            glObjectLabel(GL_BUFFER, buffer, "before");
            assertEquals(GL_INVALID_VALUE, glGetError(), "reproduce Veil's label failure");
            glNamedBufferData(buffer, 16, GL_STATIC_DRAW);
            assertEquals(GL_INVALID_OPERATION, glGetError(), "reproduce Veil's upload failure");
            assertEquals(buffer, GlObjectLifecycle.initializeGeneratedBuffer(buffer));
            assertTrue(glIsBuffer(buffer));
            glObjectLabel(GL_BUFFER, buffer, "after");
            glNamedBufferData(buffer, 16, GL_STATIC_DRAW);
            assertEquals(16, glGetNamedBufferParameteri(buffer, GL_BUFFER_SIZE));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glDeleteBuffers(buffer); }
    }

    @Test void allocationPreservesVertexArrayAndElementAndArrayBindings() {
        int vao = glCreateVertexArrays(), buffer = glCreateBuffers(), element = glCreateBuffers();
        int freshVao = glGenVertexArrays(), freshBuffer = glGenBuffers();
        try {
            glBindVertexArray(vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, element);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            assertFalse(glIsVertexArray(freshVao));
            glObjectLabel(GL_VERTEX_ARRAY, freshVao, "before");
            assertEquals(GL_INVALID_VALUE, glGetError());
            GlObjectLifecycle.initializeGeneratedVertexArray(freshVao);
            GlObjectLifecycle.initializeGeneratedBuffer(freshBuffer);
            glObjectLabel(GL_VERTEX_ARRAY, freshVao, "after");
            assertTrue(glIsVertexArray(freshVao));
            assertEquals(vao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
            assertEquals(element, glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertEquals(buffer, glGetInteger(GL_ARRAY_BUFFER_BINDING));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glDeleteVertexArrays(vao); glDeleteVertexArrays(freshVao);
            glDeleteBuffers(buffer); glDeleteBuffers(element); glDeleteBuffers(freshBuffer);
        }
    }

    @Test void allocationDoesNotConsumeUnrelatedErrors() {
        int buffer = glGenBuffers();
        try {
            glEnable(-1);
            GlObjectLifecycle.initializeGeneratedBuffer(buffer);
            assertEquals(GL_INVALID_ENUM, glGetError());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glDeleteBuffers(buffer); }
    }
}
