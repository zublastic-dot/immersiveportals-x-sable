package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
class PortalLightGpuTest {
    static long window;
    int program,atlas,vao;
    @BeforeAll static void context() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        window=glfwCreateWindow(16,16,"Portal light field test",0,0);assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();
    }
    @AfterAll static void end() { GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate(); }
    static int shader(int type,String source) {
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));return shader;
    }
    static int link(String vert,String frag) {
        int v=shader(GL_VERTEX_SHADER,vert),f=shader(GL_FRAGMENT_SHADER,frag),p=glCreateProgram();
        glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);glDeleteShader(v);glDeleteShader(f);
        assertEquals(GL_TRUE,glGetProgrami(p,GL_LINK_STATUS),glGetProgramInfoLog(p));return p;
    }
    @BeforeEach void prepare() {
        program=link("#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}",
            "#version 330 core\nout vec4 result;uniform vec3 probePosition;\n"+PortalLightShaders.FUNCTION+"\nvoid main(){result=vec4(vec3(.6)*ipPortalLightGain(probePosition),1);}");
        glUseProgram(program);vao=glGenVertexArrays();glBindVertexArray(vao);glViewport(0,0,16,16);
        atlas=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
        glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
        var values=BufferUtils.createFloatBuffer(32*32*128*4);
        for(int z=0;z<128;z++) for(int y=0;y<32;y++) for(int x=0;x<32;x++) {
            int i=((z*32+y)*32+x)*4;
            if(z<32 && y<2) { values.put(i,.2f);values.put(i+1,.3f);values.put(i+2,.6f);values.put(i+3,1); }
            if(z>=32 && z<64 && y<2) { values.put(i,.7f);values.put(i+1,.6f);values.put(i+2,.2f);values.put(i+3,1); }
        }
        glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,32,32,128,0,GL_RGBA,GL_FLOAT,values);
        glUniform1i(glGetUniformLocation(program,"ipPortalLightAtlas"),0);
        glUniform1i(glGetUniformLocation(program,"ipPortalLightCount"),1);
        for(int i=0;i<4;i++)glUniform3f(glGetUniformLocation(program,"ipPortalLightOrigin["+i+"]"),i*40,0,0);
    }
    float[] pixel(float x,float y,float z) {
        glUniform3f(glGetUniformLocation(program,"probePosition"),x,y,z);glDrawArrays(GL_TRIANGLES,0,3);
        float[] rgb=new float[4];glReadPixels(8,8,1,1,GL_RGBA,GL_FLOAT,rgb);assertEquals(GL_NO_ERROR,glGetError());return rgb;
    }
    @AfterEach void cleanup() { glDeleteTextures(atlas);glDeleteVertexArrays(vao);glDeleteProgram(program); }
    @Test void roomInteriorGetsCoolIncomingLight() { assertArrayEquals(new float[]{.12f,.18f,.36f,1},pixel(.5f,.5f,.5f),.006f); }
    @Test void roofExteriorAndUnrelatedTerrainStayUnchanged() {
        assertArrayEquals(new float[]{.6f,.6f,.6f,1},pixel(.5f,2.2f,.5f),.006f);
        assertArrayEquals(new float[]{.6f,.6f,.6f,1},pixel(40,.5f,.5f),.006f);
    }
    @Test void filteringDoesNotFadeLightingAtOpaqueCeiling() { assertArrayEquals(pixel(2,.5f,2),pixel(2,1.99f,2),.006f); }
    @Test void adjacentAtlasSlotsCannotContaminateEachOther() {
        glUniform1i(glGetUniformLocation(program,"ipPortalLightCount"),2);
        assertArrayEquals(new float[]{.42f,.36f,.12f,1},pixel(41,.5f,.05f),.006f);
        assertArrayEquals(new float[]{.12f,.18f,.36f,1},pixel(1,.5f,31.99f),.006f);
    }
    @Test void disablingForAnotherWorldOrShaderPackIsIdentity() {
        glUniform1i(glGetUniformLocation(program,"ipPortalLightCount"),0);
        assertArrayEquals(new float[]{.6f,.6f,.6f,1},pixel(.5f,.5f,.5f),.006f);
    }
    @Test void actualSodiumShaderPairCompilesWithTransport() throws Exception {
        String v=PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.vsh",resource("assets/sodium/shaders/blocks/block_layer_opaque.vsh"));
        String f=PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.fsh",resource("assets/sodium/shaders/blocks/block_layer_opaque.fsh"));
        int p=link(constants(imports(v)),constants(imports(f)));glDeleteProgram(p);
    }
    @Test void actualDhShaderPairCompilesWithTransport() throws Exception {
        String path="assets/distanthorizons/shaders/terrain/gl/frag.frag";
        String f=PortalLightShaders.dh(path,resource(path));assertTrue(f.contains("ipPortalLightGain(vertexWorldPos)"));
        int p=link(resource("assets/distanthorizons/shaders/terrain/gl/vert.vert"),f);glDeleteProgram(p);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="IP_PORTAL_TEST_COLORFUL_JAR",matches=".+")
    void installedColorfulLightingVertexLinksWithOrdinarySodiumFragment() throws Exception {
        String v=PortalLightShaders.sodium("colorful_lighting_sodium_compat:blocks/block_layer_opaque.vsh",resource("assets/colorful_lighting_sodium_compat/shaders/blocks/block_layer_opaque.vsh"));
        assertTrue(v.contains("ipPortalLightPosition=position;"));
        assertTrue(v.contains("colorful_sample_lightmap("),"retain Colorful Lighting's actual light calculation");
        String f=PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.fsh",resource("assets/sodium/shaders/blocks/block_layer_opaque.fsh"));
        int p=link(constants(imports(v)),constants(imports(f)));glDeleteProgram(p);
    }
    static String constants(String s) { int n=s.indexOf('\n');return s.substring(0,n+1)+"#define USE_VERTEX_COMPRESSION\n#define USE_FOG\n#define MAX_TEXTURE_LOD_BIAS 4\n"+s.substring(n+1); }
    static String resource(String name) throws IOException {
        if(name.startsWith("assets/colorful_lighting_sodium_compat/")) {
            try(var zip=new java.util.zip.ZipFile(System.getenv("IP_PORTAL_TEST_COLORFUL_JAR"))) {
                try(var in=zip.getInputStream(zip.getEntry(name))) {return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
            }
        }
        try(var in=PortalLightGpuTest.class.getClassLoader().getResourceAsStream(name)){ assertNotNull(in,name);return new String(in.readAllBytes(),StandardCharsets.UTF_8); }
    }
    static String imports(String s) throws IOException {
        var matcher=Pattern.compile("#import <([^:]+):([^>]+)>").matcher(s);var out=new StringBuilder();int last=0;
        while(matcher.find()) {out.append(s,last,matcher.start()).append(imports(resource("assets/"+matcher.group(1)+"/shaders/"+matcher.group(2))));last=matcher.end();}
        return out.append(s.substring(last)).toString();
    }
}
