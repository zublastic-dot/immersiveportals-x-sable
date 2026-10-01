package qouteall.imm_ptl.core.lighting;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
class PortalLightShadersTest {
    @Test void unrecognizedShadersStayByteIdentical() {
        String source="#version 330\nvoid main(){customGI();}";
        assertEquals(source,PortalLightShaders.sodium("shaderpack:terrain",source));
        assertEquals(source,PortalLightShaders.dh("assets/distanthorizons/shaders/terrain/gl/frag.frag",source));
    }
    @Test void transformationsAreIdempotent() {
        String source="#version 330\nvoid main(){color *= v_Color;}";
        String name="sodium:blocks/block_layer_opaque.fsh";
        String patched=PortalLightShaders.sodium(name,source);
        assertNotEquals(source,patched);assertEquals(patched,PortalLightShaders.sodium(name,patched));
        assertTrue(patched.startsWith("#version 330"));
    }

    /** Actual production GLSL, sampled through a floating-point framebuffer. */
    @Nested
    @EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AmbientFirstGl {
        long window;
        int program,atlas,vao,framebuffer,target,lightmap;
        final float[] sky={.4f,.3f,.2f},ambient={-.35f,-.23f,-.08f};

        @BeforeAll void context() {
            assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
            glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(16,16,"Portal ambient-first shader test",0,0);
            assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();
        }
        @AfterAll void end() {
            GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
        }
        @BeforeEach void prepare() {
            program=PortalLightGpuTest.link("""
                #version 330 core
                void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}
                """, "#version 330 core\nout vec4 result;uniform vec3 probePosition,nativeLight,nativeSky,emitter;\n"
                +PortalLightShaders.FUNCTION+"\nvoid main(){result=vec4(nativeLight+ipPortalLightDelta(probePosition,nativeLight,nativeSky,emitter),1);}");
            vao=glGenVertexArrays();glBindVertexArray(vao);
            target=glGenTextures();glBindTexture(GL_TEXTURE_2D,target);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,(java.nio.FloatBuffer)null);
            framebuffer=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));glViewport(0,0,1,1);
            atlas=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
            field(new float[][]{ambient},new float[][]{ambient});
        }
        @AfterEach void cleanup() {
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(framebuffer);glDeleteTextures(target);
            glDeleteTextures(atlas);if(lightmap!=0){glDeleteTextures(lightmap);lightmap=0;}
            glDeleteVertexArrays(vao);glDeleteProgram(program);assertEquals(GL_NO_ERROR,glGetError());
        }
        void field(float[][] totals,float[][] ambients) {
            assertEquals(totals.length,ambients.length);
            var values=BufferUtils.createFloatBuffer(32*32*256*4);
            for(int slot=0;slot<totals.length;slot++)for(int bank=0;bank<2;bank++)
                for(int z=0;z<32;z++)for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                    int offset=(((z+slot*32+bank*128)*32+y)*32+x)*4;
                    float[] rgb=bank==0?totals[slot]:ambients[slot];
                    for(int c=0;c<3;c++)values.put(offset+c,rgb[c]);values.put(offset+3,1);
                }
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,32,32,256,0,GL_RGBA,GL_FLOAT,values);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"ipPortalLightAtlas"),0);
            glUniform1i(glGetUniformLocation(program,"ipPortalLightCount"),totals.length);
            for(int i=0;i<4;i++)glUniform3f(glGetUniformLocation(program,"ipPortalLightOrigin["+i+"]"),0,0,0);
        }
        float[] render(float[] a,float[] e) {
            glUseProgram(program);set("nativeSky",a);set("emitter",e);set("nativeLight",nativeLight(a,e));
            set("probePosition",new float[]{.5f,.5f,.5f});return pixel();
        }
        void set(String name,float[] v){glUniform3f(glGetUniformLocation(program,name),v[0],v[1],v[2]);}
        float[] pixel(){glDrawArrays(GL_TRIANGLES,0,3);float[] result=new float[4];glReadPixels(0,0,1,1,GL_RGBA,GL_FLOAT,result);return result;}
        float[] nativeLight(float[] a,float[] e){float[] v=new float[3];for(int c=0;c<3;c++)v[c]=a[c]+e[c]*Math.max(.3f,1-a[0]);return v;}
        float[] expected(float[] a,float[] e,float[] total,float[] amb) {
            float gain=Math.max(.3f,1-Math.max(0,a[0]+amb[0]));float[] result={0,0,0,1};
            for(int c=0;c<3;c++)result[c]=Math.max(0,a[c]+total[c]+e[c]*gain);return result;
        }

        @Test void localEmitterUsesCorrectedAmbientAndRespondsWithoutAnAtlasRefresh() {
            float[] red={.7f,.13f,.02f},blue={.03f,.2f,.8f};
            assertArrayEquals(expected(sky,new float[3],ambient,ambient),render(sky,new float[3]),.002f);
            assertArrayEquals(expected(sky,red,ambient,ambient),render(sky,red),.002f);
            assertArrayEquals(expected(sky,blue,ambient,ambient),render(sky,blue),.002f);
            assertArrayEquals(expected(sky,new float[3],ambient,ambient),render(sky,new float[3]),.002f);
        }
        @Test void importedEmissionDoesNotSuppressTheLocalEmitterGain() {
            float[] total={-.1f,.02f,.17f},emitter={.7f,.4f,.1f};
            field(new float[][]{total},new float[][]{ambient});
            assertArrayEquals(expected(sky,emitter,total,ambient),render(sky,emitter),.002f);
        }
        @Test void partialAmbientReplacementAndGainFloorUseCurrentSky() {
            float[] partial={-.175f,-.115f,-.04f},emitter={.5f,.4f,.3f};
            field(new float[][]{partial},new float[][]{partial});
            assertArrayEquals(expected(sky,emitter,partial,partial),render(sky,emitter),.002f);
            float[] bright={.9f,.6f,.4f},delta={-.1f,-.1f,-.1f};
            field(new float[][]{delta},new float[][]{delta});
            assertArrayEquals(expected(bright,emitter,delta,delta),render(bright,emitter),.002f);
        }
        @Test void overlapAppliesEachRegionsGainBeforeChoosingTheCorrection() {
            float[] first={.1f,.1f,.1f},second={0,0,0},firstAmbient={0,0,0},secondAmbient={-.35f,-.23f,-.08f};
            float[] emitter={.8f,.5f,.1f};
            field(new float[][]{first,second},new float[][]{firstAmbient,secondAmbient});
            float[] a=expected(sky,emitter,first,firstAmbient),b=expected(sky,emitter,second,secondAmbient);
            float[] result=render(sky,emitter);
            for(int c=0;c<3;c++)assertEquals(Math.max(a[c],b[c]),result[c],.002f);
        }
        @Test void noFieldInfluencePreservesNativeSample() {
            float[] emitter={.7f,.2f,.4f},nativeValue=nativeLight(sky,emitter);
            render(sky,emitter);glUniform1i(glGetUniformLocation(program,"ipPortalLightCount"),0);
            assertArrayEquals(new float[]{nativeValue[0],nativeValue[1],nativeValue[2],1},pixel(),.00001f);
        }
        @Test void zeroAmbientChangePreservesNativeInterpolationResidual() {
            float[] total={.1f,.02f,.03f},nativeValue={.63f,.46f,.42f};
            field(new float[][]{total},new float[][]{new float[3]});
            render(sky,new float[]{.7f,.2f,.4f});set("nativeLight",nativeValue);
            assertArrayEquals(new float[]{nativeValue[0]+total[0],nativeValue[1]+total[1],nativeValue[2]+total[2],1},pixel(),.002f);
        }
        @Test void occupancyDoesNotLeakGainAcrossTheRoomWall() {
            var empty=BufferUtils.createFloatBuffer(4);
            for(int bank=0;bank<2;bank++)glTexSubImage3D(GL_TEXTURE_3D,0,1,0,bank*128,1,1,1,GL_RGBA,GL_FLOAT,empty);
            float[] emitter={.7f,.2f,.1f};render(sky,emitter);set("probePosition",new float[]{.99f,.5f,.5f});
            assertArrayEquals(expected(sky,emitter,ambient,ambient),pixel(),.002f);
            set("probePosition",new float[]{1.25f,.5f,.5f});float[] n=nativeLight(sky,emitter);
            assertArrayEquals(new float[]{n[0],n[1],n[2],1},pixel(),.00001f);
        }

        @Test @EnabledIfEnvironmentVariable(named="IP_PORTAL_TEST_COLORFUL_JAR",matches=".+")
        void actualColorfulHelperPackedChannelsAndFallbacksRenderCorrectly() throws Exception {
            String helper=PortalLightGpuTest.resource("assets/colorful_lighting_sodium_compat/shaders/include/colored_light.glsl");
            String vertex="""
                #version 330 core
                uniform sampler2D u_LightTex;
                uniform uvec4 packedInput;
                out vec4 v_Color;
                """+helper+"""
                void main(){
                    vec3 _vert_position=vec3(0.5),translation=vec3(0);
                    vec3 position = _vert_position + translation;
                    uvec4 _vert_colorful_light=packedInput;
                    vec2 _vert_tex_light_coord=vec2(0.5);
                    vec4 _vert_color=vec4(1);
                    v_Color = _vert_color * colorful_sample_lightmap(u_LightTex, _vert_colorful_light, _vert_tex_light_coord);
                    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);
                }
                """;
            String fragment="#version 330 core\nin vec4 v_Color;out vec4 result;void main(){vec4 color=vec4(1);color *= v_Color;result=color;}";
            glDeleteProgram(program);
            program=PortalLightGpuTest.link(PortalLightShaders.sodium("colorful_lighting_sodium_compat:blocks/block_layer_opaque.vsh",vertex),
                PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.fsh",fragment));
            field(new float[][]{ambient},new float[][]{ambient});
            lightmap=glGenTextures();glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,lightmap);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            var values=BufferUtils.createFloatBuffer(16*16*4);
            for(int i=0;i<256;i++)values.put(sky[0]).put(sky[1]).put(sky[2]).put(1);values.flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,16,16,0,GL_RGBA,GL_FLOAT,values);
            glUniform1i(glGetUniformLocation(program,"u_LightTex"),1);glActiveTexture(GL_TEXTURE0);
            int r=237,g=91,b=167,skyLevel=12;float[] emitter={(float)Math.pow(r/255f,1.3),(float)Math.pow(g/255f,1.3),(float)Math.pow(b/255f,1.3)};
            int packed=glGetUniformLocation(program,"packedInput");
            glUniform4ui(packed,r,g,((b&15)<<4)|skyLevel,(15<<4)|(b>>4));
            assertArrayEquals(expected(sky,emitter,ambient,ambient),pixel(),.002f);
            glUniform4ui(packed,r,g,((b&15)<<4)|skyLevel,(13<<4)|(b>>4));
            assertArrayEquals(expected(sky,new float[3],ambient,ambient),pixel(),.002f);
            glUniform4ui(packed,0,0,skyLevel,15<<4);
            assertArrayEquals(expected(sky,new float[3],ambient,ambient),pixel(),.002f);
        }
    }
}
