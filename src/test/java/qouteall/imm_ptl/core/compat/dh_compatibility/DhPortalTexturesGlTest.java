package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Mip-coloured atlas makes the installed terrain shader's block-boundary bands measurable. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhPortalTexturesGlTest {
    private static long window;
    private static String source;
    private static final int SIZE = 32;

    @BeforeAll static void open() throws Exception {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(SIZE, SIZE, "IP/Sable texture regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try (var stream = DhPortalTexturesGlTest.class.getResourceAsStream("/" + DhPortalTextures.TERRAIN_SHADER)) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @AfterAll static void close() {
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }
    private static int compile(int kind, String text) {
        int shader = glCreateShader(kind);
        glShaderSource(shader, text); glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }
    private static float[] draw(int face, boolean patched, boolean stableSampling) {
        return draw(face, patched, stableSampling, false, 4.0f);
    }

    private static float[] draw(int face, boolean patched, boolean stableSampling, boolean contrastAtlas, float blockSpan) {
        return draw(face, patched, stableSampling, contrastAtlas, blockSpan, .91f);
    }

    private static float[] draw(int face, boolean patched, boolean stableSampling, boolean contrastAtlas,
                                float blockSpan, float blockPhase) {
        int vertex = compile(GL_VERTEX_SHADER, """
            #version 330 core
            out vec4 vPos;
            out vec4 vertexColor;
            out vec3 vertexWorldPos;
            out vec3 vBlockPos;
            flat out uint vNormalIndex;
            flat out uint vTextureTileId;
            uniform int face;
            uniform float blockSpan;
            uniform float blockPhase;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vec2 block = p * blockSpan + blockPhase;
                vBlockPos = vec3(block.x, block.y, block.y);
                vPos = vec4(vBlockPos, 1.0);
                vertexWorldPos = vec3(0, 0, -100);
                vertexColor = vec4(0.5, 0.5, 0.5, 1);
                vNormalIndex = uint(face);
                vTextureTileId = 1u;
                gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
            }
            """);
        int fragment = compile(GL_FRAGMENT_SHADER, patched ? DhPortalTextures.patchTerrainShader(source) : source);
        int program = glCreateProgram();
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        int vao = glGenVertexArrays(), atlas = glGenTextures();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, atlas);
        if (contrastAtlas) {
            // Match DH's real atlas size, filtering and unrestricted generated
            // mip chain. Tile 1 is neutral; unrelated neighbouring tiles are dark.
            int width = 4096, height = 256;
            float[] pixels = new float[width * height * 4];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                float ratio = x >= 16 && x < 32 && y < 16 ? .5f : .125f;
                pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = ratio;
                pixels[offset + 3] = 1;
            }
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_FLOAT, pixels);
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        } else for (int level = 0; level <= 4; level++) {
            int width = 4096 >> level, height = 16 >> level;
            float[] pixels = new float[width * height * 4];
            for (int i = 0; i < pixels.length; i += 4) {
                pixels[i] = pixels[i + 1] = pixels[i + 2] = .1f + level * .15f;
                pixels[i + 3] = 1;
            }
            glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, pixels);
        }
        if (!contrastAtlas) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 4);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, SIZE, SIZE);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
            glUseProgram(program); glBindVertexArray(vao);
            glUniform1i(glGetUniformLocation(program, "face"), face);
            glUniform1f(glGetUniformLocation(program, "blockSpan"), blockSpan);
            glUniform1f(glGetUniformLocation(program, "blockPhase"), blockPhase);
            glUniform1i(glGetUniformLocation(program, "uBlockAtlas"), 0);
            if (patched) glUniform1i(glGetUniformLocation(program, "uIpContinuousTextureGradients"), stableSampling ? 1 : 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            float[] pixels = new float[SIZE * SIZE * 4];
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            assertEquals(GL_NO_ERROR, glGetError());
            return pixels;
        } finally {
            glUseProgram(0); glBindVertexArray(0); glDeleteTextures(atlas); glDeleteVertexArrays(vao);
            glDeleteProgram(program); glDeleteShader(vertex); glDeleteShader(fragment);
        }
    }
    private static float variation(float[] pixels) {
        float min = 1, max = 0;
        for (int i = 0; i < pixels.length; i += 4) { min = Math.min(min, pixels[i]); max = Math.max(max, pixels[i]); }
        return max - min;
    }
    @Test void wrappedUvMipBandsDisappearForAllSixFaceOrientations() {
        assertNotEquals(source, DhPortalTextures.patchTerrainShader(source));
        for (int face = 0; face < 6; face++) {
            float[] control = draw(face, false, false);
            float[] repair = draw(face, true, true);
            assertTrue(variation(control) > .1f, "Installed shader must reproduce false mip bands on face " + face);
            assertTrue(variation(repair) < .01f, "Continuous texture footprint on face " + face);
            assertArrayEquals(control, draw(face, true, false), .001f, "Shader-pack opt-out retains installed sampling");
        }
    }

    @Test void distantPaleTileCannotSampleDarkerNeighbouringMaterials() {
        for (int face = 0; face < 6; face++) {
            for (float span : new float[]{16, 128, 512}) {
                float[] pixels = draw(face, true, true, true, span);
                for (int i = 0; i < pixels.length; i += 4) {
                    assertEquals(.5f, pixels[i], .008f,
                        "Neutral tile must preserve terrain colour: face=" + face + ", span=" + span);
                }
            }
        }
    }

    @Test void invertedFacesAtExactBlockBoundariesStayInsideTheirTile() {
        for (int face = 0; face < 6; face++) {
            float[] pixels = draw(face, true, true, true, 32, .5f);
            for (int i = 0; i < pixels.length; i += 4) {
                assertEquals(.5f, pixels[i], .008f, "Tile edge on face " + face);
            }
        }
    }
}
