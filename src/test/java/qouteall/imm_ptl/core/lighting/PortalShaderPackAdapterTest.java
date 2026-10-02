package qouteall.imm_ptl.core.lighting;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

class PortalShaderPackAdapterTest {
    private static final String PACK = "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";

    @AfterEach void clear() { PortalShaderPackAdapter.clear(); }

    @Test void admissionIsExactAndUnknownProgramsAreIdentity() {
        assertTrue(PortalShaderPackAdapter.supports(PACK));
        assertTrue(PortalShaderPackAdapter.supports(PACK + ".zip"));
        assertFalse(PortalShaderPackAdapter.supports(PACK + " modified"));
        var source = List.of("#version 330", "void main(){}");
        assertSame(source, PortalShaderPackAdapter.patch(PACK, "/world-1/terrain.fsh", source, ""));
        assertSame(source, PortalShaderPackAdapter.patch("another", "/world-1/terrain.fsh", source, ""));
    }

    @Test void noDirectionUntilARealConstantWasObservedAndReloadClearsIt() {
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
        PortalShaderPackAdapter.observePreprocessed(PACK, "const float sunPathRotation = -37.5; float tAmin = fract(sunAngle - 0.033333333);");
        assertEquals(-37.5, PortalShaderPackAdapter.sunPathRotationDegrees().orElseThrow());
        assertEquals(PortalShaderPackAdapter.ClockMode.SUN_ANGLE, PortalShaderPackAdapter.clockMode().orElseThrow());
        PortalShaderPackAdapter.clear();
        PortalShaderPackAdapter.observePreprocessed("unknown", "const float sunPathRotation = 35.;");
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
        PortalShaderPackAdapter.observePreprocessed(PACK, "const float sunPathRotation = 1e400;");
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
    }

    @Test void noShadowQualityUsesTheActualWorldTimeBranch() {
        PortalShaderPackAdapter.observePreprocessed(PACK,
            "const float sunPathRotation = 20.; float timeAngle = worldTimeSmooth / 24000.0;");
        assertEquals(PortalShaderPackAdapter.ClockMode.WORLD_TIME, PortalShaderPackAdapter.clockMode().orElseThrow());
        assertEquals(20, PortalShaderPackAdapter.sunPathRotationDegrees().orElseThrow());
    }

    @Test void changedFogSignatureLeavesTheWholeProgramUntouched() {
        var source = List.of("#define NETHER", "// Complementary Shaders by EminGT",
            "vec4 GetNetherStorm(vec3 changed) {", "netherStorm.a += stormSample;", "}");
        assertSame(source, PortalShaderPackAdapter.patch(PACK, "/world-1/composite1.fsh", source, ""));
        assertTrue(PortalShaderPackAdapter.status().get("/world-1/composite1.fsh").contains("Storm signature changed"));
    }

    @Test void ambiguousObservedConstantIsNotUsed() {
        PortalShaderPackAdapter.observePreprocessed(PACK,
            "const float sunPathRotation = 1.; const float sunPathRotation = 2.;");
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
    }

    /**
     * The licensed shader is read from an explicitly supplied owner path, never
     * checked into the repository. This uses Iris' real include/preprocessor/AST
     * stages and the driver compiler, not anchor-only or copied miniature shaders.
     */
    @Nested
    @EnabledIfSystemProperty(named = "ipsable.shaderPack", matches = ".+")
    @EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class InstalledPackGl {
        long window;
        IncludeProcessor includes;
        IncludeGraph graph;
        Map<String, String> settings;
        final List<StringPair> defines = List.of(new StringPair("IS_IRIS", ""),
            new StringPair("MC_VERSION", "12101"), new StringPair("IRIS_VERSION", "10814"),
            new StringPair("MC_OS_WINDOWS", ""), new StringPair("DISTANT_HORIZONS", ""), new StringPair("MC_GL_VERSION", "460"),
            new StringPair("MC_GLSL_VERSION", "460"),
            new StringPair("DH_BLOCK_UNKNOWN", "0"), new StringPair("DH_BLOCK_LEAVES", "1"),
            new StringPair("DH_BLOCK_STONE", "2"), new StringPair("DH_BLOCK_WOOD", "3"),
            new StringPair("DH_BLOCK_METAL", "4"), new StringPair("DH_BLOCK_DIRT", "5"),
            new StringPair("DH_BLOCK_LAVA", "6"), new StringPair("DH_BLOCK_DEEPSLATE", "7"),
            new StringPair("DH_BLOCK_SNOW", "8"), new StringPair("DH_BLOCK_SAND", "9"),
            new StringPair("DH_BLOCK_TERRACOTTA", "10"), new StringPair("DH_BLOCK_NETHER_STONE", "11"),
            new StringPair("DH_BLOCK_WATER", "12"), new StringPair("DH_BLOCK_GRASS", "13"),
            new StringPair("DH_BLOCK_AIR", "14"), new StringPair("DH_BLOCK_ILLUMINATED", "15"));

        @BeforeAll void setup() throws Exception {
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
            var configField = net.irisshaders.iris.Iris.class.getDeclaredField("irisConfig");
            configField.setAccessible(true);
            configField.set(null, new net.irisshaders.iris.config.IrisConfig(Path.of("unused-test-iris.properties"), Path.of("unused-test-exclusions.json")));
            assertTrue(glfwInit());
            glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
            window = glfwCreateWindow(8, 8, "Portal shader pack compilation", 0, 0);
            assertNotEquals(0, window);glfwMakeContextCurrent(window);GL.createCapabilities();
            var entries = ImmutableList.<AbsolutePackPath>builder();
            for (String name : List.of("gbuffers_terrain", "dh_terrain", "deferred1", "composite1", "gbuffers_water"))
                for (String extension : List.of(".vsh", ".fsh"))
                    entries.add(AbsolutePackPath.fromAbsolutePath("/world-1/" + name + extension));
            Path root = Path.of(System.getProperty("ipsable.shaderPack"));
            graph = new IncludeGraph(root, entries.build(), false);
            assertTrue(graph.getFailures().isEmpty(), graph.getFailures().toString());
            settings = new java.util.HashMap<>();
            Path options = root.getParent().resolveSibling(root.getParent().getFileName() + ".txt");
            if (Files.isRegularFile(options)) {
                var properties = new java.util.Properties();
                try (var stream = Files.newInputStream(options)) { properties.load(stream); }
                for (String key : properties.stringPropertyNames()) settings.put(key, properties.getProperty(key));
            }
            includes = new IncludeProcessor(new ShaderPackOptions(graph, settings).getIncludes());
        }

        @AfterAll void teardown() {
            GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
        }

        String source(String name, String extension, boolean patched) {
            String path = "/world-1/" + name + extension;
            var original = includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath(path));
            assertNotNull(original, path);
            List<String> result = patched ? PortalShaderPackAdapter.patch(PACK, path, original) : original;
            if (patched && extension.equals(".fsh")) {
                assertNotSame(original, result, path + " did not admit the exact installed pack");
                assertEquals(result, PortalShaderPackAdapter.patch(PACK, path, result), "idempotence");
                assertSame(original, includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath(path)), "cache changed");
            }
            String expanded = String.join("\n", result) + "\n";
            // JCPP can recover after an unbalanced directive; reject that before
            // its recovery could silently drop a helper or change option scope.
            int depth = 0;
            for (String line : result) {
                String trimmed = line.trim();
                if (trimmed.matches("#if(?:def|ndef)?\\s+.*")) depth++;
                if (trimmed.startsWith("#endif")) assertTrue(--depth >= 0, path + " unmatched endif");
            }
            assertEquals(0, depth, path + " unbalanced directives");
            String preprocessed = JcppProcessor.glslPreprocessSource(expanded, defines);
            if (patched && extension.equals(".fsh")) assertTrue(preprocessed.contains("ipSunCount"), path);
            PortalShaderPackAdapter.observePreprocessed(PACK, preprocessed);
            return preprocessed;
        }

        Map<PatchShaderType, String> transformed(String name, boolean patched) {
            String vertex = source(name, ".vsh", patched), fragment = source(name, ".fsh", patched);
            if (name.equals("dh_terrain")) return TransformPatcher.patchDHTerrain(name, vertex, null, null, null,
                fragment, new Object2ObjectOpenHashMap<>());
            if (name.equals("deferred1") || name.equals("composite1")) return TransformPatcher.patchComposite(name,
                vertex, null, fragment, name.equals("deferred1") ? TextureStage.DEFERRED : TextureStage.COMPOSITE_AND_FINAL,
                new Object2ObjectOpenHashMap<>());
            return TransformPatcher.patchSodium(name, vertex, null, null, null, fragment,
                AlphaTest.ALWAYS, new Object2ObjectOpenHashMap<>());
        }

        void verify(String name) {
            // A stock-source compile guards against blaming the adapter for an
            // unsupported fixture option/environment before testing the patch.
            for (boolean patched : new boolean[]{false, true}) {
                var sources = transformed(name, patched);
                int program = glCreateProgram();
                try {
                    for (var stage : List.of(PatchShaderType.VERTEX, PatchShaderType.FRAGMENT)) {
                        int shader = glCreateShader(stage == PatchShaderType.VERTEX ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
                        try {
                            glShaderSource(shader, sources.get(stage));glCompileShader(shader);
                            assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS),
                                name + " patched=" + patched + " " + stage + "\n" + glGetShaderInfoLog(shader));
                            glAttachShader(program, shader);
                        } finally { glDeleteShader(shader); }
                    }
                    glLinkProgram(program);
                    assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), name + "\n" + glGetProgramInfoLog(program));
                } finally { glDeleteProgram(program); }
            }
        }

        @Test void portalFogPrefixRequiresActualApertureCrossingAndNeverAppliesInsideTheWorld() {
            int program = glCreateProgram();int vao = glGenVertexArrays();
            String vertex = "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0.,1.);}";
            String fragment = """
                #version 330 core
                uniform int ipSunCount, ipSunPortalView;
                uniform vec3 ipSunOrigin[4],ipSunPlane[4],ipSunInward[4],ipSunU[4],ipSunV[4];
                uniform vec2 ipSunHalfSize[4];
                uniform vec3 endpoint;
                uniform float roomWeight;
                out vec4 result;
                float ipSunWeight(vec3 p) { return p.z>=4.0 ? roomWeight : 0.0; }
                """ + PortalShaderPackAdapter.FOG_HELPER + "\nvoid main(){result=vec4(ipSunFogFraction(endpoint),0,0,1);}";
            try {
                for (var stage : Map.of(GL_VERTEX_SHADER,vertex,GL_FRAGMENT_SHADER,fragment).entrySet()) {
                    int shader=glCreateShader(stage.getKey());
                    try { glShaderSource(shader,stage.getValue());glCompileShader(shader);
                        assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));
                        glAttachShader(program,shader);
                    } finally { glDeleteShader(shader); }
                }
                glLinkProgram(program);assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));
                glUseProgram(program);glBindVertexArray(vao);glViewport(0,0,1,1);
                glUniform1i(glGetUniformLocation(program,"ipSunCount"),1);
                glUniform3f(glGetUniformLocation(program,"ipSunOrigin[0]"),-16,-16,4);
                glUniform3f(glGetUniformLocation(program,"ipSunPlane[0]"),0,0,4);
                glUniform3f(glGetUniformLocation(program,"ipSunInward[0]"),0,0,1);
                glUniform3f(glGetUniformLocation(program,"ipSunU[0]"),1,0,0);
                glUniform3f(glGetUniformLocation(program,"ipSunV[0]"),0,1,0);
                glUniform2f(glGetUniformLocation(program,"ipSunHalfSize[0]"),2,2);
                assertEquals(0.4f,fogPixel(program,1,0,0,10,0),0.005f);
                assertEquals(0f,fogPixel(program,0,0,0,10,0),0.005f);
                assertEquals(0f,fogPixel(program,1,10,0,10,0),0.005f);
                assertEquals(0f,fogPixel(program,1,0,0,-10,0),0.005f);
                assertEquals(1f,fogPixel(program,1,0,0,10,1),0.005f);
                assertEquals(0.6f,fogPixel(program,0,0,0,10,1),0.005f);
            } finally { glUseProgram(0);glBindVertexArray(0);glDeleteVertexArrays(vao);glDeleteProgram(program); }
        }

        float fogPixel(int program,int portal,float x,float y,float z,float weight) {
            glUniform1i(glGetUniformLocation(program,"ipSunPortalView"),portal);
            glUniform3f(glGetUniformLocation(program,"endpoint"),x,y,z);
            glUniform1f(glGetUniformLocation(program,"roomWeight"),weight);
            glDrawArrays(GL_TRIANGLES,0,3);
            float[] pixel = new float[4];glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,pixel);
            assertEquals(GL_NO_ERROR,glGetError());return pixel[0];
        }

        void verifyOptions(Map<String, String> overrides) {
            IncludeProcessor previous = includes;
            var selected = new java.util.HashMap<>(settings);selected.putAll(overrides);
            try { includes = new IncludeProcessor(new ShaderPackOptions(graph, selected).getIncludes());verify("gbuffers_terrain"); }
            finally { includes = previous; }
        }

        @Test void fullTerrainWithSourceMoonPhaseCompilesAndLinks() {
            verifyOptions(Map.of("MOON_PHASE_INF_LIGHT", "true"));
        }
        @Test void fullTerrainWithoutShadowsUsesWorldTimeAndCompiles() {
            verifyOptions(Map.of("SHADOW_QUALITY", "-1"));
            assertEquals(PortalShaderPackAdapter.ClockMode.WORLD_TIME, PortalShaderPackAdapter.clockMode().orElseThrow());
        }

        @Test void actualPackLowAndUltraShadowKernelsAndSmoothingOptionsCompile() {
            for(String quality:List.of("0","1","5"))
                verifyOptions(Map.of("SHADOW_QUALITY",quality,"SHADOW_SMOOTHING","1"));
        }
        @Test void actualPackNonTemporalShadowKernelCompiles() {
            verifyOptions(Map.of("SHADOW_QUALITY","2","TAA_DEFINE","0","SHADOW_SMOOTHING","4"));
        }
        @Test void fullSodiumTerrainCompilesAndLinks() { verify("gbuffers_terrain"); }
        @Test void fullDhTerrainCompilesAndLinks() { verify("dh_terrain"); }
        @Test void fullBorderFogPassCompilesAndLinks() { verify("deferred1"); }
        @Test void fullNetherStormPassCompilesAndLinks() { verify("composite1"); }
        @Test void fullWaterPassCompilesAndLinks() { verify("gbuffers_water"); }
    }
}
