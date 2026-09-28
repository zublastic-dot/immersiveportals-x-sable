package ipl.sable.render;

import com.mojang.blaze3d.shaders.Program;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

class VeilShaderCompatibilityTest {
    // Simulated 1.3.2's installed outline_diagram.vsh (GLSL version supplied by Veil).
    private static final String DIAGRAM = """
        #version 150
        uniform vec2 InSize;
        out vec2 texCoord;
        out vec2 oneTexel;
        void main() {
            vec2 uv = vec2(gl_VertexID & 1, gl_VertexID & 2);
            gl_Position = vec4(uv * vec2(3.0) - vec2(1.0), 0.0, 1.0);
            texCoord = uv * vec2(1.5);
            oneTexel = 1.0 / InSize;
        }
        """;
    private static final String GEOMETRY = """
        #version 150
        in vec3 Position;
        uniform mat4 ModelViewMat;
        uniform mat4 ProjMat;
        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        }
        """;
    private Object previous;
    private Field configs;

    @BeforeEach void loadProductionTransformations() throws Exception {
        configs = ShaderCodeTransformation.class.getDeclaredField("configs");
        configs.setAccessible(true);
        previous = configs.get(null);
        try (var stream = getClass().getResourceAsStream(
            "/assets/immersive_portals/shaders/shader_transformation.yaml")) {
            assertNotNull(stream);
            var parsed = new Yaml().loadAs(new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                ShaderCodeTransformation.ConfigsObj.class);
            configs.set(null, parsed.configs);
        }
    }
    @AfterEach void restore() throws Exception { configs.set(null, previous); }

    private String preprocess(String name, boolean vertex, String input) throws Exception {
        var context = (ShaderPreProcessor.Context) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ShaderPreProcessor.VeilContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "name" -> ResourceLocation.parse(name);
                case "isVertex" -> vertex;
                case "isFragment" -> !vertex;
                default -> throw new AssertionError(method);
            });
        var tree = GlslParser.parse(input);
        new IplVeilShaderPreProcessor().modify(context, tree);
        return tree.toSourceString();
    }

    @Test void fullscreenDiagramRemainsFreeOfWorldPositionInputs() throws Exception {
        String output = preprocess("simulated:contraption_diagram/outline_diagram", true, DIAGRAM);
        assertFalse(output.contains("ModelViewMat"));
        assertFalse(output.contains("iportal_ClippingEquation"));
        assertTrue(output.contains("gl_VertexID"));
        assertFalse(ShaderCodeTransformation.shouldAddUniform("simulated:contraption_diagram/outline_diagram"));
    }

    @Test void worldGeometryRetainsAllThreeClipPlanes() throws Exception {
        String output = preprocess("simulated:laser_pointer/lens", true, GEOMETRY);
        assertTrue(output.contains("iportal_ClippingEquation"));
        assertTrue(output.contains("ipl_subLevelClipEquation"));
        for (int i = 0; i < 3; i++) assertTrue(output.contains("gl_ClipDistance[" + i + "]"));
    }

    @Test void vertexOnlyEntryDoesNotClaimAFragmentTransformation() throws Exception {
        String id = "simulated:laser_pointer/lens";
        assertTrue(ShaderCodeTransformation.hasTransformation(Program.Type.VERTEX, id));
        assertFalse(ShaderCodeTransformation.hasTransformation(Program.Type.FRAGMENT, id));
        String fragment = "#version 150\nout vec4 color;\nvoid main() { color = vec4(1.0); }";
        assertEquals(GlslParser.parse(fragment).toSourceString(), preprocess(id, false, fragment));
    }

    @Test @EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
    void processedScreenAndWorldShadersBothCompileOnGpu() throws Exception {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32, 32, "IP/Sable shader regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window); GL.createCapabilities();
        try {
            for (String source : new String[]{
                preprocess("simulated:contraption_diagram/outline_diagram", true, DIAGRAM),
                preprocess("simulated:laser_pointer/lens", true, GEOMETRY)}) {
                int shader = glCreateShader(GL_VERTEX_SHADER);
                try {
                    glShaderSource(shader, source); glCompileShader(shader);
                    assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
                } finally { glDeleteShader(shader); }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            GL.setCapabilities(null); glfwMakeContextCurrent(0);
            glfwDestroyWindow(window); glfwTerminate();
        }
    }
}
