package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhIrisProjectionDiagnosticsGlTest {
    private static long window;
    @BeforeAll static void setup() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(8, 8, "DH matrix evidence", 0, 0);assertNotEquals(0, window);
        glfwMakeContextCurrent(window);GL.createCapabilities();
    }
    @AfterAll static void teardown() {
        GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
    }
    @Test void readsActualGpuMatricesDetectsStaleViewAndLeavesProgramAndUniformsUntouched() {
        int vs = shader(GL_VERTEX_SHADER, "#version 330 core\nuniform mat4 dhProjection,gbufferModelView;"
            + "void main(){gl_Position=dhProjection*gbufferModelView*vec4(float(gl_VertexID),1.,2.,1.);}");
        int fs = shader(GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 color;void main(){color=vec4(1.);}");
        int program = glCreateProgram();glAttachShader(program, vs);glAttachShader(program, fs);glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        try {
            var projection = new Matrix4f().perspective((float)Math.toRadians(70), 16f/9f, 7, 4096);
            var oldView = new Matrix4f().rotateY(.4f);
            var newView = new Matrix4f().rotateY(.8f);
            glUseProgram(program);
            glUniformMatrix4fv(glGetUniformLocation(program, "dhProjection"), false, projection.get(new float[16]));
            glUniformMatrix4fv(glGetUniformLocation(program, "gbufferModelView"), false, oldView.get(new float[16]));
            String result = DhIrisProjectionDiagnostics.captureMatrices(program, projection, projection,
                newView, projection, newView);
            assertTrue(result.contains("matrixLayout=column-major"));
            assertTrue(result.contains("dhProjectionMaxDelta=0.0"), result);
            assertFalse(result.contains("gbufferModelViewMaxDelta=0.0"), result);
            assertTrue(result.contains("iris_ProjectionMatrix=inactive"), result);
            assertTrue(result.contains("dhProjectionInverse=inactive"), result);
            assertTrue(result.length() < 6000, result.length() + " chars");
            assertEquals(program, glGetInteger(GL_CURRENT_PROGRAM));
            float[] retained = new float[16];glGetUniformfv(program, glGetUniformLocation(program, "gbufferModelView"), retained);
            assertArrayEquals(oldView.get(new float[16]), retained, 0);
            assertEquals(GL_NO_ERROR, glGetError());
            glUseProgram(0);
            assertEquals("matrices=unavailable:not-current-program",
                DhIrisProjectionDiagnostics.captureMatrices(program, projection, projection, newView, projection, newView));
            assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { glUseProgram(0);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs); }
    }
    private static int shader(int type, String source) {
        int shader = glCreateShader(type);glShaderSource(shader, source);glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));return shader;
    }
}
