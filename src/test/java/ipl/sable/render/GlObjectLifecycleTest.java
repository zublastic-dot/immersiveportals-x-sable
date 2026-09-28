package ipl.sable.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.mixin.client.VeilBufferLifecycleMixin;
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

    @Test void allocationWrapperMaterializesNamesReturnedFromTheBatchCache() throws Exception {
        // IP's cancellable HEAD hook returns names from glGen* batches without
        // reaching vanilla's RETURN. Exercise the actual outer wrapper with
        // those reserved names, before any caller has bound either object.
        int[] buffers = new int[2], vaos = new int[2];
        glGenBuffers(buffers);
        glGenVertexArrays(vaos);
        int[] calls = {0};
        try {
            var bufferWrapper = VeilBufferLifecycleMixin.class.getDeclaredMethod(
                "ipl$initializeBuffer", Operation.class);
            var vaoWrapper = VeilBufferLifecycleMixin.class.getDeclaredMethod(
                "ipl$initializeVertexArray", Operation.class);
            bufferWrapper.setAccessible(true);
            vaoWrapper.setAccessible(true);
            Operation<Integer> cachedBuffer = args -> { calls[0]++; return buffers[1]; };
            Operation<Integer> cachedVao = args -> { calls[0]++; return vaos[1]; };
            assertFalse(glIsBuffer(buffers[1]));
            assertFalse(glIsVertexArray(vaos[1]));
            assertEquals(buffers[1], bufferWrapper.invoke(null, cachedBuffer));
            assertEquals(vaos[1], vaoWrapper.invoke(null, cachedVao));
            assertEquals(2, calls[0], "each underlying allocation runs once");
            assertTrue(glIsBuffer(buffers[1]));
            assertTrue(glIsVertexArray(vaos[1]));
            assertFalse(glIsBuffer(buffers[0]), "unused cached names remain reserved");
            assertFalse(glIsVertexArray(vaos[0]));
            glObjectLabel(GL_BUFFER, buffers[1], "cached buffer");
            glObjectLabel(GL_VERTEX_ARRAY, vaos[1], "cached VAO");
            glNamedBufferData(buffers[1], 16, GL_STATIC_DRAW);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glDeleteBuffers(buffers);
            glDeleteVertexArrays(vaos);
        }
    }
}
