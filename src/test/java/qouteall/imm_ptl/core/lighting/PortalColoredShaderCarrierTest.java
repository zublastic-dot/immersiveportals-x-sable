package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

class PortalColoredShaderCarrierTest {
    static final String PACK = "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";
    // Test lighting contract; native Colorful generates its own real hue replacement around this call.
    static final String FIXTURE = """
        #version 330 core
        uniform vec2 lmCoord;
        uniform vec3 playerPos;
        out vec4 result;
        vec3 blocklightCol = vec3(1.0);
        void DoLighting(inout vec4 color, vec2 lightmap) { color.rgb = blocklightCol * lightmap.x; }
        void main() {
            vec4 color = vec4(1);
            vec2 lmCoordM = lmCoord;
            DoLighting(color, lmCoordM);
            result = color;
        }
        """;

    static String nativePatch(String source) throws Exception {
        return nativePatch(source, false);
    }
    static String nativePatch(String source, boolean vertex) throws Exception {
        String path = System.getProperty("colorfulJar", "");
        if (path.isBlank()) path = System.getenv("IP_PORTAL_TEST_COLORFUL_JAR");
        Assumptions.assumeTrue(path != null && !path.isBlank(), "Installed Colorful 2.5.1 contract input required");
        assertTrue(Files.isRegularFile(Path.of(path)));
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(path).toUri().toURL()},
            PortalColoredShaderCarrierTest.class.getClassLoader())) {
            var api = Class.forName("dev.colorfullighting.compat.iris.IrisShaderCompat", true, loader);
            return vertex ? (String) api.getMethod("patchVertex", String.class, boolean.class).invoke(null, source, true)
                : (String) api.getMethod("patchFragment", String.class).invoke(null, source);
        }
    }

    @Test void exactInstalledNativeHuePatchReceivesOnlyOneCarrier() throws Exception {
        String nativeSource = nativePatch(FIXTURE);
        assertTrue(nativeSource.contains("colorfulLightingSodiumCompat_Color"));
        assertFalse(nativeSource.contains("lmCoordM.x ="), "This installed native path lacks an intensity carrier");
        String result = PortalColoredShaderCarrier.patch(PACK, nativeSource);
        assertNotEquals(nativeSource, result);
        assertTrue(result.contains("ipPortalRgbCarrier(playerPos, colorfulLightingSodiumCompat_Color, lmCoordM.x)"));
        assertSame(result, PortalColoredShaderCarrier.patch(PACK, result));
    }
    @Test void missingOrAmbiguousNativeContractAndUnsupportedPacksRemainUntouched() throws Exception {
        String nativeSource = nativePatch(FIXTURE);
        assertSame(nativeSource, PortalColoredShaderCarrier.patch("unsupported", nativeSource));
        assertSame(FIXTURE, PortalColoredShaderCarrier.patch(PACK, FIXTURE));
        String changed = nativeSource.replace("vec2 lmCoordM = lmCoord;", "vec2 another = lmCoord;");
        assertSame(changed, PortalColoredShaderCarrier.patch(PACK, changed));
        String ambiguous = nativeSource + "\nvoid main() {}";
        assertSame(ambiguous, PortalColoredShaderCarrier.patch(PACK, ambiguous));
    }

    @Nested
    @EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NativeGpu {
        long window;
        int vao, nativeProgram, patchedProgram;
        @BeforeAll void setup() throws Exception {
            assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(8, 8, "Native Colorful portal brightness", 0, 0);
            assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
            String nativeSource = nativePatch(FIXTURE);
            String vertex = """
                #version 330 core
                uniform vec3 testRgb;
                out vec3 colorfulLightingSodiumCompat_Color;
                void main() {
                    colorfulLightingSodiumCompat_Color = testRgb;
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2 - 1, 0, 1);
                }
                """;
            nativeProgram = PortalLightGpuTest.link(vertex, nativeSource);
            patchedProgram = PortalLightGpuTest.link(vertex, PortalColoredShaderCarrier.patch(PACK, nativeSource));
            vao = glGenVertexArrays(); glBindVertexArray(vao); glViewport(0, 0, 8, 8);
        }
        @AfterAll void end() {
            glUseProgram(0); glDeleteProgram(nativeProgram); glDeleteProgram(patchedProgram);
            glBindVertexArray(0); glDeleteVertexArrays(vao); GL.setCapabilities(null);
            glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
        }
        float[] draw(int program, int rgb, float scalar, int count, float point) {
            glUseProgram(program);
            glUniform3f(glGetUniformLocation(program,"testRgb"),(rgb >>> 16 & 255) / 255f,(rgb >>> 8 & 255) / 255f,(rgb & 255) / 255f);
            glUniform2f(glGetUniformLocation(program,"lmCoord"),scalar,0);
            glUniform3f(glGetUniformLocation(program,"playerPos"),point,0,0);
            glUniform1i(glGetUniformLocation(program,"ipPortalRgbCarrierCount"),count);
            glUniform3f(glGetUniformLocation(program,"ipPortalRgbCarrierMin[0]"),-1,-1,-1);
            glUniform3f(glGetUniformLocation(program,"ipPortalRgbCarrierMax[0]"),1,1,1);
            glDrawArrays(GL_TRIANGLES,0,3);
            float[] color=new float[4];glReadPixels(4,4,1,1,GL_RGBA,GL_FLOAT,color);
            assertEquals(GL_NO_ERROR,glGetError());return color;
        }
        @Test void productionGreenFieldWithZeroVanillaLightFailsNativeAndWorksThroughCarrier() {
            int rgb=PortalColoredLighting.attenuated(0,15,0,2);
            assertEquals(0,draw(nativeProgram,rgb,0,0,0)[1],.001f);
            float[] result=draw(patchedProgram,rgb,0,1,0);
            assertTrue(result[1]>.3f);assertEquals(0,result[0],.001f);assertEquals(0,result[2],.001f);
        }
        @Test void disabledOrOutsideReceivingFieldRetainsOriginalScalarLighting() {
            int rgb=PortalColoredLighting.attenuated(0,15,0,2);
            assertArrayEquals(draw(nativeProgram,rgb,.2f,0,0),draw(patchedProgram,rgb,.2f,0,0),.001f);
            assertArrayEquals(draw(nativeProgram,rgb,.2f,0,0),draw(patchedProgram,rgb,.2f,1,10),.001f);
        }
        @Test void sourceColorRemovalAndWeakerLightDoNotLeaveAWhiteCarrier() {
            float[] green=draw(patchedProgram,PortalColoredLighting.attenuated(0,15,0,2),0,1,0);
            float[] red=draw(patchedProgram,PortalColoredLighting.attenuated(15,0,0,2),0,1,0);
            assertTrue(green[1]>.3f);assertTrue(red[0]>.3f);assertEquals(0,red[1],.001f);
            assertEquals(0,draw(patchedProgram,0,0,1,0)[0],.001f);
            float strong=draw(patchedProgram,0x777777,0,1,0)[0];
            float weak=draw(patchedProgram,0x333333,0,1,0)[0];
            assertTrue(strong>weak && weak>0);
        }
    }
}
