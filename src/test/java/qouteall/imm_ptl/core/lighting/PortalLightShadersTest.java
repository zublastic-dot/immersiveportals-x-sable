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
                """, "#version 330 core\nout vec4 result;uniform vec3 probePosition,nativeLight,nativeSky,emitter;uniform vec2 vanillaUv;\n"
                +PortalLightShaders.FUNCTION+"\nvoid main(){result=vec4(nativeLight+ipPortalLightDelta(probePosition,nativeLight,nativeSky,emitter,vanillaUv),1);}");
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
            glUniform2f(glGetUniformLocation(program,"vanillaUv"),-1,-1);
        }
        @AfterEach void cleanup() {
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(framebuffer);glDeleteTextures(target);
            glDeleteTextures(atlas);if(lightmap!=0){glDeleteTextures(lightmap);lightmap=0;}
            glDeleteVertexArrays(vao);glDeleteProgram(program);assertEquals(GL_NO_ERROR,glGetError());
        }
        void field(float[][] totals,float[][] ambients) {
            assertEquals(totals.length,ambients.length);
            var values=BufferUtils.createFloatBuffer(32*64*256*4);
            for(int slot=0;slot<totals.length;slot++)for(int bank=0;bank<2;bank++)
                for(int z=0;z<32;z++)for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                    int offset=(((z+slot*32+bank*128)*64+y)*32+x)*4;
                    float[] rgb=bank==0?totals[slot]:ambients[slot];
                    for(int c=0;c<3;c++)values.put(offset+c,rgb[c]);values.put(offset+3,1);
                }
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,32,64,256,0,GL_RGBA,GL_FLOAT,values);
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
        void vanillaData(int slot,float[] metadata,float[][] nativePalette,float[][] referencePalette) {
            var cells=BufferUtils.createFloatBuffer(32*32*32*4);
            for(int i=0;i<32*32*32;i++)cells.put(metadata[0]).put(metadata[1]).put(metadata[2]).put(1);
            cells.flip();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_3D,atlas);
            glTexSubImage3D(GL_TEXTURE_3D,0,0,32,slot*32,32,32,32,GL_RGBA,GL_FLOAT,cells);
            for(int which=0;which<2;which++) {
                var values=BufferUtils.createFloatBuffer(16*16*4);
                for(float[] rgb:which==0?nativePalette:referencePalette)values.put(rgb).put(1);
                values.flip();glTexSubImage3D(GL_TEXTURE_3D,0,0,32,128+slot*32+which,16,16,1,GL_RGBA,GL_FLOAT,values);
            }
        }
        /** Deliberately nonlinear palettes; ambient, sky and emission cannot be split by a fixed floor subtraction. */
        float[][] palette(boolean nether,boolean daytime) {
            float[][] result=new float[256][3];
            for(int s=0;s<16;s++)for(int b=0;b<16;b++) {
                float sky=s/15f,level=b/15f,light=level/(4-3*level);
                float[] base=nether?new float[]{.38f,.25f,.17f}:
                    daytime?new float[]{.03f+.52f*sky,.03f+.55f*sky,.03f+.60f*sky}:
                        new float[]{.015f+.07f*sky,.02f+.10f*sky,.025f+.20f*sky};
                float[] emission=nether?new float[]{1.5f*light,1.55f*light,1.6f*light}:
                    new float[]{1.3f*light,1.05f*light*light+.12f*light,.60f*light*light*light+.05f*light};
                for(int c=0;c<3;c++)result[s*16+b][c]=Math.min(.99f,base[c]+emission[c]);
            }
            return result;
        }
        float[] sampled(float[][] palette,float sky,float block) {
            float s=Math.clamp(sky,0,15),b=Math.clamp(block,0,15);int si=(int)s,bi=(int)b;
            float[] result=new float[3];
            for(int c=0;c<3;c++) {
                float lo=palette[si*16+bi][c]*(1-(b-bi))+palette[si*16+Math.min(15,bi+1)][c]*(b-bi);
                float hi=palette[Math.min(15,si+1)*16+bi][c]*(1-(b-bi))+palette[Math.min(15,si+1)*16+Math.min(15,bi+1)][c]*(b-bi);
                result[c]=lo*(1-(s-si))+hi*(s-si);
            }
            return result;
        }
        float[] delta(float[] a,float[] b,float weight){float[] d=new float[3];for(int c=0;c<3;c++)d[c]=(a[c]-b[c])*weight;return d;}
        void knownPair(float[][] nativePalette,float[][] referencePalette,float remoteSky,float remoteBlock,float weight) {
            float[] amb=delta(sampled(referencePalette,remoteSky,0),sampled(nativePalette,0,0),weight);
            float[] total=delta(sampled(referencePalette,remoteSky,remoteBlock),sampled(nativePalette,0,0),weight);
            field(new float[][]{total},new float[][]{amb});
            vanillaData(0,new float[]{remoteSky,remoteBlock,weight},nativePalette,referencePalette);
        }
        float[] renderVanilla(float[][] nativePalette,float sky,float block) {
            glUseProgram(program);set("nativeSky",new float[3]);set("emitter",new float[3]);
            set("nativeLight",sampled(nativePalette,sky,block));set("probePosition",new float[]{.5f,.5f,.5f});
            glUniform2f(glGetUniformLocation(program,"vanillaUv"),(block+.5f)/16,(sky+.5f)/16);return pixel();
        }
        void assertRgb(float[] rgb,float[] actual){assertArrayEquals(new float[]{rgb[0],rgb[1],rgb[2],1},actual,.003f);}

        @Test void vanillaHeldLightReevaluatesWarmEmissionWithoutAnAtlasRefresh() {
            float[][] n=palette(true,false),r=palette(false,false);
            knownPair(n,r,13,0,1);
            for(float b:new float[]{0,4,9,13,15,6,0})assertRgb(sampled(r,13,b),renderVanilla(n,0,b));
            float[] old=sampled(n,0,15),a=delta(sampled(r,13,0),sampled(n,0,0),1);
            assertTrue(old[2]+a[2]>old[0]+a[0],"old fixed-floor subtraction makes saturated local light blue");
            assertTrue(sampled(r,13,15)[0]>sampled(r,13,15)[2],"reference emission remains warm");
        }
        @Test void vanillaReferenceRespondsToDayNightPaletteChanges() {
            float[][] n=palette(true,false);
            for(boolean day:new boolean[]{false,true}) {
                float[][] r=palette(false,day);knownPair(n,r,11,0,1);
                assertRgb(sampled(r,11,8),renderVanilla(n,0,8));
            }
        }
        @Test void vanillaHandoffTakesMaximumScalarLightInsteadOfAddingTwice() {
            float[][] n=palette(true,false),r=palette(false,false);
            knownPair(n,r,12,13,1);
            for(float local:new float[]{0,9,13,14,15})
                assertRgb(sampled(r,12,Math.max(13,local)),renderVanilla(n,0,local));
        }
        @Test void reverseOverworldKeepsItsOwnLocalEmissionAndSky() {
            float[][] r=palette(false,false);field(new float[][]{new float[3]},new float[][]{new float[3]});
            vanillaData(0,new float[]{0,6,1},r,r);
            for(float local:new float[]{6,9,14,15})assertRgb(sampled(r,15,local),renderVanilla(r,15,local));
            assertRgb(sampled(r,15,6),renderVanilla(r,15,2));
        }
        @Test void partialWeightInterpolatesWholeEmissionResponseAndPreservesNativeResidual() {
            float[][] n=palette(true,false),r=palette(false,false);float weight=.35f;
            knownPair(n,r,12,0,weight);
            float[] original=sampled(n,0,10),desired=sampled(r,12,10),expected=new float[3];
            for(int c=0;c<3;c++)expected[c]=original[c]*(1-weight)+desired[c]*weight;
            assertRgb(expected,renderVanilla(n,0,10));
            float[] residual={.01f,-.015f,.023f};
            for(int c=0;c<3;c++){original[c]+=residual[c];expected[c]+=residual[c];}
            set("nativeLight",original);assertRgb(expected,pixel());
        }
        @Test void fractionalUvAndMetadataUseBilinearPaletteCoordinates() {
            float[][] n=palette(true,false),r=palette(false,false);
            knownPair(n,r,8.25f,3.5f,1);
            assertRgb(sampled(r,8.25f,7.75f),renderVanilla(n,0,7.75f));
        }
        @Test void scalarOccupancyBlocksWallLeakAndNormalizesAdjacentEmptyMetadata() {
            float[][] n=palette(true,false),r=palette(false,false);knownPair(n,r,10,0,1);
            var empty=BufferUtils.createFloatBuffer(4);
            glTexSubImage3D(GL_TEXTURE_3D,0,1,0,0,1,1,1,GL_RGBA,GL_FLOAT,empty);
            glTexSubImage3D(GL_TEXTURE_3D,0,1,0,128,1,1,1,GL_RGBA,GL_FLOAT,empty);
            glTexSubImage3D(GL_TEXTURE_3D,0,1,32,0,1,1,1,GL_RGBA,GL_FLOAT,empty);
            renderVanilla(n,0,12);set("probePosition",new float[]{.99f,.5f,.5f});assertRgb(sampled(r,10,12),pixel());
            set("probePosition",new float[]{1.25f,.5f,.5f});assertRgb(sampled(n,0,12),pixel());
        }
        @Test void scalarCompositionMergesCompletePerRegionResults() {
            float[][] n=palette(true,false),night=palette(false,false),day=palette(false,true);
            float[] a=delta(sampled(night,12,0),sampled(n,0,0),1),b=delta(sampled(day,5,0),sampled(n,0,0),1);
            field(new float[][]{a,b},new float[][]{a,b});
            vanillaData(0,new float[]{12,13,1},n,night);vanillaData(1,new float[]{5,0,1},n,day);
            float[] first=sampled(night,12,13),second=sampled(day,5,9),expected=new float[3];
            for(int c=0;c<3;c++)expected[c]=Math.max(first[c],second[c]);
            assertRgb(expected,renderVanilla(n,0,9));
        }
        @Test void missingScalarMetadataRetainsLegacyDeltaEvenWithCurrentUv() {
            float[][] n=palette(true,false);float[] total={-.2f,.05f,.1f};
            field(new float[][]{total},new float[][]{ambient});
            float[] expected=sampled(n,0,9);for(int c=0;c<3;c++)expected[c]=Math.max(0,expected[c]+total[c]);
            assertRgb(expected,renderVanilla(n,0,9));
        }
        @Test void transformedSodiumVertexUsesActualSampleCoordinatesAndNoExtraHalfTexelBias() {
            String vertex="""
                #version 330 core
                uniform sampler2D u_LightTex;uniform vec2 drawUv;out vec4 v_Color;
                void main(){
                    vec3 _vert_position=vec3(0.5),translation=vec3(0);
                    vec3 position = _vert_position + translation;
                    vec4 _vert_color=vec4(1);vec2 _vert_tex_light_coord=drawUv;
                    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
                    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);
                }
                """;
            String fragment="#version 330 core\nin vec4 v_Color;out vec4 result;void main(){vec4 color=vec4(1);color *= v_Color;result=color;}";
            glDeleteProgram(program);program=PortalLightGpuTest.link(PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.vsh",vertex),
                PortalLightShaders.sodium("sodium:blocks/block_layer_opaque.fsh",fragment));
            float[][] n=palette(true,false),r=palette(false,false);knownPair(n,r,10.5f,0,1);
            lightmap=glGenTextures();glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,lightmap);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            var pixels=BufferUtils.createFloatBuffer(256*4);for(float[] rgb:n)pixels.put(rgb).put(1);pixels.flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,16,16,0,GL_RGBA,GL_FLOAT,pixels);
            glUniform1i(glGetUniformLocation(program,"u_LightTex"),1);glActiveTexture(GL_TEXTURE0);
            for(float level:new float[]{0,5,11.75f,15}) {
                glUniform2f(glGetUniformLocation(program,"drawUv"),(level+.5f)/16,.5f/16);
                assertRgb(sampled(r,10.5f,level),pixel());
            }
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
            float[][] nativePalette=new float[256][3];for(int i=0;i<256;i++)nativePalette[i]=sky.clone();
            float[][] reference=palette(false,false);
            vanillaData(0,new float[]{12,4,.65f},nativePalette,reference);
            // Valid packed RGB keeps the Colorful path even when scalar metadata exists.
            glUniform4ui(packed,r,g,((b&15)<<4)|skyLevel,(15<<4)|(b>>4));
            assertArrayEquals(expected(sky,emitter,ambient,ambient),pixel(),.002f);
            float[] referenceOn=sampled(reference,12,7.5f),referenceOff=sampled(reference,12,0),fallback=new float[3];
            for(int c=0;c<3;c++)fallback[c]=Math.max(0,sky[c]+ambient[c]+.65f*(referenceOn[c]-referenceOff[c]));
            glUniform4ui(packed,r,g,((b&15)<<4)|skyLevel,(13<<4)|(b>>4));assertRgb(fallback,pixel());
            glUniform4ui(packed,0,0,skyLevel,15<<4);assertRgb(fallback,pixel());
        }
    }
}
