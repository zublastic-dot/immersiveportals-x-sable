package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DhPortalShaderGlTest {
    private long window;
    @BeforeAll void setup() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_COMPAT_PROFILE);
        window=glfwCreateWindow(8,8,"DH portal coverage regression",0,0);assertNotEquals(0,window);
        glfwMakeContextCurrent(window);GL.createCapabilities();
    }
    @AfterAll void teardown() {
        GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
        DhPortalShaderCoverage.enable();DhPortalShaderPackAdapter.clear();
    }
    @Test void fragmentFadeClosesActualGapAndResetsBetweenPortalAndMainDraws() {
        String vertex="#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}";
        String fragment="#version 330 core\nuniform float far,distanceToCamera;\nout vec4 result;\n"
            +DhPortalShaderPackAdapter.HELPER+"\nvoid main(){result=vec4(ipDhPortalFade(distanceToCamera,far),0,0,1);}";
        int program=link("fade pixels",Map.of(PatchShaderType.VERTEX,vertex,PatchShaderType.FRAGMENT,fragment));
        int vao=glGenVertexArrays();
        try {
            glUseProgram(program);glBindVertexArray(vao);glViewport(0,0,1,1);
            glUniform1f(glGetUniformLocation(program,"far"),112);
            DhPortalShaderCoverage.bind();assertEquals(0,pixel(program,31),.005);
            try(var portal=DhPortalShaderCoverage.enter(()->30.2)) {
                DhPortalShaderCoverage.bind();assertEquals(1,pixel(program,31),.005);
                assertEquals(.5,pixel(program,(float)(29.2*5/6)),.012);
                assertEquals(112,glGetUniformf(program,glGetUniformLocation(program,"far")));
                try(var nested=DhPortalShaderCoverage.enter(()->-1)) {
                    DhPortalShaderCoverage.bind();assertEquals(0,pixel(program,31),.005);
                }
                DhPortalShaderCoverage.bind();assertEquals(1,pixel(program,31),.005);
            }
            DhPortalShaderCoverage.bind();assertEquals(0,pixel(program,31),.005);
            float stock=pixel(program,56);
            try(var full=DhPortalShaderCoverage.enter(()->112)) {
                DhPortalShaderCoverage.bind();assertEquals(stock,pixel(program,56),.00001);
            }
            try(var unavailable=DhPortalShaderCoverage.enter(()->0)) {
                DhPortalShaderCoverage.bind();assertEquals(1,pixel(program,1),.005);
            }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally { glUseProgram(0);glBindVertexArray(0);glDeleteVertexArrays(vao);glDeleteProgram(program); }
    }
    private float pixel(int program,float distance) {
        glUniform1f(glGetUniformLocation(program,"distanceToCamera"),distance);glDrawArrays(GL_TRIANGLES,0,3);
        float[] rgba=new float[4];glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,rgba);return rgba[0];
    }
    @Test void anUnadaptedLiveProgramNeverQueriesCoverageEvenWhenAnotherDimensionWasAdapted() {
        String vertex="#version 330 core\nvoid main(){gl_Position=vec4(0,0,0,1);}";
        String fragment="#version 330 core\nout vec4 result;\nvoid main(){result=vec4(1);}";
        int program=link("unadapted dimension",Map.of(PatchShaderType.VERTEX,vertex,PatchShaderType.FRAGMENT,fragment));
        try {
            DhPortalShaderPackAdapter.patch(DhPortalShaderPackAdapterTest.PACK,"/custom_dimension/dh_terrain.fsh",
                List.of("#version 330 compatibility","// Complementary Shaders by EminGT","void main() {",
                    DhPortalShaderPackAdapter.ANCHOR,"}"));
            assertTrue(DhPortalShaderPackAdapter.admitted(DhPortalShaderPackAdapterTest.PACK));
            glUseProgram(program);
            assertEquals(-1,glGetUniformLocation(program,DhPortalShaderCoverage.UNIFORM));
            try(var portal=DhPortalShaderCoverage.enter(()->{fail("Unadapted programs must not scan any world");return 0;})) {
                DhPortalShaderCoverage.bind();
            }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally { glUseProgram(0);glDeleteProgram(program); }
    }
    @Test @EnabledIfSystemProperty(named="ipsable.shaderPack",matches=".+")
    void installedTerrainAndWaterComposeWithSunlightThroughActualIrisCompiler() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        var config=net.irisshaders.iris.Iris.class.getDeclaredField("irisConfig");config.setAccessible(true);
        config.set(null,new net.irisshaders.iris.config.IrisConfig(Path.of("unused-test-iris.properties"),Path.of("unused-test-exclusions.json")));
        Path root=Path.of(System.getProperty("ipsable.shaderPack"));
        var entries=ImmutableList.<AbsolutePackPath>builder();
        for(String dimension:List.of("world-1","world0","world1"))
            for(String program:List.of("dh_terrain","dh_water"))
                for(String extension:List.of(".vsh",".fsh"))
                    entries.add(AbsolutePackPath.fromAbsolutePath("/"+dimension+"/"+program+extension));
        var graph=new IncludeGraph(root,entries.build(),false);assertTrue(graph.getFailures().isEmpty(),graph.getFailures().toString());
        var settings=new HashMap<String,String>();
        Path options=root.getParent().resolveSibling(root.getParent().getFileName()+".txt");
        if(Files.isRegularFile(options)) {
            var properties=new Properties();try(var in=Files.newInputStream(options)) { properties.load(in); }
            for(String key:properties.stringPropertyNames()) settings.put(key,properties.getProperty(key));
        }
        var includes=new IncludeProcessor(new ShaderPackOptions(graph,settings).getIncludes());
        List<StringPair> defines=new ArrayList<>(List.of(new StringPair("IS_IRIS",""),new StringPair("MC_VERSION","12101"),
            new StringPair("IRIS_VERSION","10814"),new StringPair("MC_OS_WINDOWS",""),new StringPair("DISTANT_HORIZONS",""),
            new StringPair("MC_GL_VERSION","460"),new StringPair("MC_GLSL_VERSION","460")));
        String[] blocks={"UNKNOWN","LEAVES","STONE","WOOD","METAL","DIRT","LAVA","DEEPSLATE","SNOW","SAND","TERRACOTTA",
            "NETHER_STONE","WATER","GRASS","AIR","ILLUMINATED"};
        for(int i=0;i<blocks.length;i++) defines.add(new StringPair("DH_BLOCK_"+blocks[i],Integer.toString(i)));
        for(String dimension:List.of("world-1","world0","world1")) {
            for(String name:List.of("dh_terrain","dh_water")) {
                for(boolean patched:new boolean[]{false,true}) {
                    Map<String,String> sources=new HashMap<>();
                    for(String extension:List.of(".vsh",".fsh")) {
                        String path="/"+dimension+"/"+name+extension;
                        var original=includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath(path));assertNotNull(original,path);
                        List<String> result=original;
                        if(patched) {
                            result=PortalShaderPackAdapter.patch(DhPortalShaderPackAdapterTest.PACK,path,result);
                            var beforeCoverage=result;
                            result=DhPortalShaderPackAdapter.patch(DhPortalShaderPackAdapterTest.PACK,path,result);
                            if(extension.equals(".fsh")) {
                                assertNotSame(beforeCoverage,result,path+" missing DH adapter");
                                assertSame(result,DhPortalShaderPackAdapter.patch(DhPortalShaderPackAdapterTest.PACK,path,result));
                            }
                        }
                        assertSame(original,includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath(path)),"Include cache mutated");
                        String text=JcppProcessor.glslPreprocessSource(String.join("\n",result)+"\n",defines);
                        if(patched&&extension.equals(".fsh")) assertTrue(text.contains("ipDhCoverageBlocks"),path);
                        sources.put(extension,text);
                    }
                    var transformed=TransformPatcher.patchDHTerrain(name,sources.get(".vsh"),null,null,null,sources.get(".fsh"),new Object2ObjectOpenHashMap<>());
                    int linked=link(dimension+"/"+name+" patched="+patched,transformed);
                    if(patched) assertTrue(glGetUniformLocation(linked,DhPortalShaderCoverage.UNIFORM)>=0,"Live coverage uniform optimized away");
                    glDeleteProgram(linked);
                }
            }
        }
    }
    private int link(String label,Map<PatchShaderType,String> sources) {
        int program=glCreateProgram();
        try {
            for(var stage:List.of(PatchShaderType.VERTEX,PatchShaderType.FRAGMENT)) {
                int shader=glCreateShader(stage==PatchShaderType.VERTEX?GL_VERTEX_SHADER:GL_FRAGMENT_SHADER);
                try {
                    glShaderSource(shader,sources.get(stage));driverCall(label+" "+stage,()->glCompileShader(shader));
                    assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),label+" "+stage+"\n"+glGetShaderInfoLog(shader));
                    glAttachShader(program,shader);
                } finally { glDeleteShader(shader); }
            }
            driverCall(label+" LINK",()->glLinkProgram(program));
            assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),label+"\n"+glGetProgramInfoLog(program));return program;
        } catch(Throwable failure) { glDeleteProgram(program);throw failure; }
    }
    private void driverCall(String label,Runnable work) {
        assertNotNull(System.getProperty("org.gradle.test.worker"));
        var done=new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog=Thread.ofPlatform().daemon().unstarted(()-> {
            try { Thread.sleep(25_000); } catch(InterruptedException finished) { return; }
            if(!done.get()) { System.err.println("DH_COVERAGE_DRIVER_TIMEOUT "+label);System.err.flush();Runtime.getRuntime().halt(124); }
        });
        System.out.println("DH_COVERAGE_DRIVER_START "+label);System.out.flush();watchdog.start();
        try { work.run(); } finally { done.set(true);watchdog.interrupt(); }
    }
}
