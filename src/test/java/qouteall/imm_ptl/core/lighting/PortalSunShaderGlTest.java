package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Tests the production GLSL transport, not a second implementation of ray visibility. */
@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
class PortalSunShaderGlTest {
    static long window;
    int program,atlas,shadow,vao;
    float[] cells;
    @BeforeAll static void context() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        window=glfwCreateWindow(16,16,"Portal sunlight transport",0,0);
        assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();
    }
    @AfterAll static void end() {GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();}
    @BeforeEach void prepare() throws Exception {
        String resource;
        try(var in=getClass().getClassLoader().getResourceAsStream("assets/immersive_portals/shaders/portal_sun.glsl")) {
            assertNotNull(in);resource=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        program=PortalLightGpuTest.link("#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}",
            "#version 330 core\nout vec4 result;uniform vec3 probe;uniform vec3 probeNormal;uniform int mode;\n"
            +"vec3 ipSunPackScene(int i,float sky,float direct,vec3 normal,float viewDistance,float localBlock,float emission,out float blockMultiplier);\nfloat ipSunPackDirectionShade(int i,vec3 normal);\n"+resource
            +"\nvec3 ipSunPackScene(int i,float sky,float direct,vec3 normal,float viewDistance,float localBlock,float emission,out float blockMultiplier){blockMultiplier=1.0;return vec3(.1*sky+.7*direct);}\n"
            +"float ipSunPackDirectionShade(int i,vec3 normal){return 1.0;}\n"
            +"void main(){if(mode==1)result=vec4(ipSunLightmap(probe,probeNormal,vec2(.2,.0)),0,1);else result=vec4(ipSunApply(probe,probeNormal,vec3(.3,.2,.1)),1);}");
        glUseProgram(program);vao=glGenVertexArrays();glBindVertexArray(vao);glViewport(0,0,16,16);
        atlas=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
        glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
        cells=new float[32*32*128*4];
        for(int i=0;i<32*32*32;i++) {cells[i*4]=1;cells[i*4+1]=.8f;cells[i*4+2]=1;cells[i*4+3]=1;}
        upload();
        shadow=glGenTextures();glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D_ARRAY,shadow);
        glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        byte[] light=new byte[32*32*4];java.util.Arrays.fill(light,(byte)255);
        glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R8,32,32,4,0,GL_RED,GL_UNSIGNED_BYTE,BufferUtils.createByteBuffer(light.length).put(light).flip());
        glUniform1i(glGetUniformLocation(program,"ipSunAtlas"),0);glUniform1i(glGetUniformLocation(program,"ipSunSourceShadow"),1);
        glUniform1i(glGetUniformLocation(program,"ipSunCount"),1);
        vec("ipSunOrigin[0]",0,0,0);vec("ipSunPlane[0]",-.5f,4,4);vec("ipSunInward[0]",1,0,0);
        vec("ipSunU[0]",0,0,1);vec("ipSunV[0]",0,1,0);vec("ipSunDirection[0]",-1,0,0);
        glUniform2f(glGetUniformLocation(program,"ipSunHalfSize[0]"),2,2);vec("probeNormal",0,0,0);
    }
    void vec(String n,float x,float y,float z) {glUniform3f(glGetUniformLocation(program,n),x,y,z);}
    void upload() {glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,32,32,128,0,GL_RGBA,GL_FLOAT,cells);}
    float[] pixel(float x,float y,float z) {
        vec("probe",x,y,z);glDrawArrays(GL_TRIANGLES,0,3);float[] rgba=new float[4];glReadPixels(8,8,1,1,GL_RGBA,GL_FLOAT,rgba);
        assertEquals(GL_NO_ERROR,glGetError());return rgba;
    }
    @AfterEach void cleanup(){glDeleteTextures(atlas);glDeleteTextures(shadow);glDeleteVertexArrays(vao);glDeleteProgram(program);}
    @Test void sunlightOnlyPassesInsideAperture(){
        assertEquals(.8f,pixel(5,4,4)[0],.006);
        assertEquals(.1f,pixel(5,6.001f,4)[0],.006);
        assertEquals(.1f,pixel(5,4,6)[0],.006);
    }
    @Test void concreteBetweenReceiverAndApertureCastsShadow(){
        for(int y=0;y<32;y++)for(int z=0;z<32;z++)cells[((z*32+y)*32+2)*4+3]=0;
        upload();assertEquals(.1f,pixel(5,4,4)[0],.006);assertEquals(.8f,pixel(1,4,4)[0],.006);
    }
    @Test void rayCannotCutThroughTheCornerOfAPairwiseNeighbor(){
        vec("ipSunDirection[0]",-1,-1,-1);
        assertEquals(.8f,pixel(5.5f,9.5f,9.5f)[0],.006);
        cells[((9*32+8)*32+4)*4+3]=0;
        upload();assertEquals(.1f,pixel(5.5f,9.5f,9.5f)[0],.006);
    }
    @Test void blockedSourceRemovesDirectButRetainsAmbient(){
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D_ARRAY,shadow);
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,0,0,32,32,1,GL_RED,GL_UNSIGNED_BYTE,BufferUtils.createByteBuffer(32*32));
        assertEquals(.1f,pixel(5,4,4)[0],.006);
    }
    @Test void ambientOutsideObservedRegionIsUnchanged(){
        assertArrayEquals(new float[]{.3f,.2f,.1f,1},pixel(40,4,4),.006f);
        glUniform1i(glGetUniformLocation(program,"ipSunCount"),0);
        assertArrayEquals(new float[]{.3f,.2f,.1f,1},pixel(5,4,4),.006f);
    }
    @Test void unknownReceiverCannotBorrowFieldFromAdjacentCell(){
        cells[((4*32+4)*32+5)*4+3]=0;upload();
        assertArrayEquals(new float[]{.3f,.2f,.1f,1},pixel(5.01f,4,4),.006f);
    }
    @Test void transportedBlockLightIsNotLostWithShaders(){
        glUniform1i(glGetUniformLocation(program,"mode"),1);
        assertArrayEquals(new float[]{.8f,1,0,1},pixel(5,4,4),.006f);
        assertArrayEquals(new float[]{.2f,0,0,1},pixel(40,4,4),.006f);
    }
    @Test void backFacingAndParallelRaysDoNotCreateSun(){
        vec("ipSunDirection[0]",1,0,0);assertEquals(.1f,pixel(5,4,4)[0],.006);
        vec("ipSunDirection[0]",0,1,0);assertEquals(.1f,pixel(5,4,4)[0],.006);
    }
}
