package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Exercises production storage/binding in GL 3.3; does not construct or change a Minecraft world. */
@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
class PortalShaderGpuGlTest {
    private static long window;
    private static final int[] UNPACK_KEYS={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_IMAGE_HEIGHT,
        GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES};
    private static final float[] MARKER={.75f,.5f,.25f,1};

    @BeforeAll static void context() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        window=glfwCreateWindow(16,16,"Portal shader transport test",0,0);
        assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();
    }

    @AfterAll static void end() {
        GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
    }

    @AfterEach void reset() {
        PortalShaderGpu.clear();
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
        for(int key:UNPACK_KEYS) glPixelStorei(key,key==GL_UNPACK_ALIGNMENT?4:0);
        glUseProgram(0);glActiveTexture(GL_TEXTURE0);
        assertEquals(GL_NO_ERROR,glGetError());
    }

    private static PortalShaderLighting.Region region(float value,int shadow) {
        float[] cells=new float[32*32*32*4];
        cells[0]=value;cells[1]=.25f;cells[2]=.5f;cells[3]=1;
        byte[] mask=new byte[32*32];mask[0]=(byte)shadow;
        return region(cells,mask);
    }

    private static PortalShaderLighting.Region region(float[] cells,byte[] shadow) {
        return new PortalShaderLighting.Region(null,new PortalLightField.Pos(0,40,0),cells,shadow,Vec3.ZERO,0,1);
    }

    private static float[] texel(PortalShaderGpu.Atlas atlas,int slot) {
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D);
        try {
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            var values=BufferUtils.createFloatBuffer(32*32*128*4);
            glGetTexImage(GL_TEXTURE_3D,0,GL_RGBA,GL_FLOAT,values);
            int start=slot*32*32*32*4;
            return new float[]{values.get(start),values.get(start+1),values.get(start+2),values.get(start+3)};
        } finally { glBindTexture(GL_TEXTURE_3D,previous); }
    }

    private static int shadow(PortalShaderGpu.Atlas atlas,int slot) {
        int previous=glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY,atlas.shadowTexture);
            var values=BufferUtils.createByteBuffer(32*32*4);
            glGetTexImage(GL_TEXTURE_2D_ARRAY,0,GL_RED,GL_UNSIGNED_BYTE,values);
            return Byte.toUnsignedInt(values.get(slot*32*32));
        } finally { glBindTexture(GL_TEXTURE_2D_ARRAY,previous); }
    }

    private static void mark(PortalShaderGpu.Atlas atlas,int slot) {
        int a=glGetInteger(GL_TEXTURE_BINDING_3D),b=glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            var f=BufferUtils.createFloatBuffer(4).put(MARKER);f.flip();
            glTexSubImage3D(GL_TEXTURE_3D,0,0,0,slot*32,1,1,1,GL_RGBA,GL_FLOAT,f);
            glBindTexture(GL_TEXTURE_2D_ARRAY,atlas.shadowTexture);
            var m=BufferUtils.createByteBuffer(1).put((byte)127);m.flip();
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,0,slot,1,1,1,GL_RED,GL_UNSIGNED_BYTE,m);
        } finally { glBindTexture(GL_TEXTURE_3D,a);glBindTexture(GL_TEXTURE_2D_ARRAY,b); }
    }

    @Test void allocatesBoundedFormatsWithIndependentFieldAndShadowSampling() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            assertEquals(1,atlas.update(List.of(region(.125f,255))));
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_WIDTH));
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_HEIGHT));
            assertEquals(128,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_DEPTH));
            assertEquals(GL_RGBA16F,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_INTERNAL_FORMAT));
            assertEquals(GL_LINEAR,glGetTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER));
            glBindTexture(GL_TEXTURE_2D_ARRAY,atlas.shadowTexture);
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY,0,GL_TEXTURE_WIDTH));
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY,0,GL_TEXTURE_HEIGHT));
            assertEquals(4,glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY,0,GL_TEXTURE_DEPTH));
            assertEquals(GL_R8,glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY,0,GL_TEXTURE_INTERNAL_FORMAT));
            assertEquals(GL_NEAREST,glGetTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER));
            assertArrayEquals(new float[]{.125f,.25f,.5f,1},texel(atlas,0),.001f);
            assertEquals(255,shadow(atlas,0));
        }
    }

    @Test void timeAndDirectionPublicationsDoNotUploadUnchangedArrays() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            var a=region(.125f,255);atlas.update(List.of(a));mark(atlas,0);
            var timed=new PortalShaderLighting.Region(null,a.min(),a.cells(),a.sourceShadow(),new Vec3(1,0,0),.7f,42);
            assertEquals(0,atlas.update(List.of(timed)));
            assertArrayEquals(MARKER,texel(atlas,0));assertEquals(127,shadow(atlas,0));
        }
    }

    @Test void maskAndFieldChangesUploadOnlyTheirOwnPayload() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            var a=region(.125f,255);atlas.update(List.of(a));mark(atlas,0);
            var b=region(a.cells(),new byte[32*32]);
            assertEquals(1,atlas.update(List.of(b)));
            assertArrayEquals(MARKER,texel(atlas,0));assertEquals(0,shadow(atlas,0));
            mark(atlas,0);
            assertEquals(1,atlas.update(List.of(region(region(.5f,255).cells(),b.sourceShadow()))));
            assertArrayEquals(new float[]{.5f,.25f,.5f,1},texel(atlas,0));assertEquals(127,shadow(atlas,0));
        }
    }

    @Test void allFourSlotsMoveClearAndRemainIndependent() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            var a=region(.125f,1);var b=region(.25f,2);var c=region(.5f,3);var d=region(1,4);
            assertEquals(4,atlas.update(List.of(a,b,c,d)));
            for(int i=0;i<4;i++) assertEquals(i+1,shadow(atlas,i));
            mark(atlas,0);
            assertEquals(3,atlas.update(List.of(a,d,b,c)));
            assertArrayEquals(MARKER,texel(atlas,0));assertEquals(127,shadow(atlas,0));
            assertEquals(4,shadow(atlas,1));assertEquals(2,shadow(atlas,2));assertEquals(3,shadow(atlas,3));
            assertEquals(3,atlas.update(List.of(a)));
            for(int i=1;i<4;i++) { assertArrayEquals(new float[4],texel(atlas,i));assertEquals(0,shadow(atlas,i)); }
            assertEquals(1,atlas.update(List.of()));
            assertArrayEquals(new float[4],texel(atlas,0));assertEquals(0,shadow(atlas,0));
        }
    }

    @Test void separateAtlasesCannotReuseAnotherWorldsPixels() {
        try(var a=new PortalShaderGpu.Atlas();var b=new PortalShaderGpu.Atlas()) {
            a.update(List.of(region(.125f,255)));b.update(List.of(region(.75f,0)));
            assertNotEquals(a.texture,b.texture);assertNotEquals(a.shadowTexture,b.shadowTexture);
            assertEquals(.125f,texel(a,0)[0]);assertEquals(.75f,texel(b,0)[0]);
            assertEquals(255,shadow(a,0));assertEquals(0,shadow(b,0));
        }
    }

    @Test void partialUploadFailureRestoresBothOldBanksOnRetry() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            var a=region(.125f,255);var b=region(.25f,128);atlas.update(List.of(a,b));
            var bad=region(new float[3],new byte[32*32]);
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(.75f,0),bad)));
            assertEquals(2,atlas.update(List.of(a,b)));
            assertArrayEquals(new float[]{.125f,.25f,.5f,1},texel(atlas,0));assertEquals(255,shadow(atlas,0));
            assertEquals(.25f,texel(atlas,1)[0]);assertEquals(128,shadow(atlas,1));
        }
    }

    @Test void malformedMetadataAndOversizedRegionListsFailClosed() {
        try(var atlas=new PortalShaderGpu.Atlas()) {
            var good=region(.125f,255);
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(good,good,good,good,good)));
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(null,good.sourceShadow()))));
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(good.cells(),null))));
            for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.1f}) {
                float[] cells=good.cells().clone();cells[0]=invalid;
                assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(cells,good.sourceShadow()))));
            }
            float[] cells=good.cells().clone();cells[3]=.5f;
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(cells,good.sourceShadow()))));
            assertThrows(IllegalArgumentException.class,()->atlas.update(List.of(region(good.cells(),new byte[31*32]))));
            assertEquals(1,atlas.update(List.of(good)));
            assertEquals(255,shadow(atlas,0));
        }
    }

    private static int shader(int type,String text) {
        int id=glCreateShader(type);glShaderSource(id,text);glCompileShader(id);
        assertEquals(GL_TRUE,glGetShaderi(id,GL_COMPILE_STATUS),glGetShaderInfoLog(id));return id;
    }

    private static int program(int nativeCount) {
        StringBuilder fragment=new StringBuilder("#version 330 core\nuniform int ipSunCount;uniform sampler3D ipSunAtlas;uniform sampler2DArray ipSunSourceShadow;uniform sampler2D nativeImages[")
            .append(nativeCount).append("];uniform isampler2D nativeInteger;out vec4 color;void main(){color=texture(ipSunAtlas,vec3(.5))*float(ipSunCount)+texture(ipSunSourceShadow,vec3(.5,.5,0))+vec4(texelFetch(nativeInteger,ivec2(0),0));");
        for(int i=0;i<nativeCount;i++) fragment.append("color+=texture(nativeImages[").append(i).append("],vec2(.5));");
        fragment.append('}');
        return program(fragment.toString());
    }

    private static int program(String fragment) {
        int vertex=shader(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
        int frag=shader(GL_FRAGMENT_SHADER,fragment);int program=glCreateProgram();
        glAttachShader(program,vertex);glAttachShader(program,frag);glLinkProgram(program);
        assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));
        glDeleteShader(vertex);glDeleteShader(frag);glUseProgram(program);return program;
    }

    @Test void samplerArraysAndIntegerSamplersAreReserved() {
        int program=program(2),limit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        try {
            glUniform1i(glGetUniformLocation(program,"nativeImages[0]"),limit-1);
            glUniform1i(glGetUniformLocation(program,"nativeImages[1]"),limit-3);
            glUniform1i(glGetUniformLocation(program,"nativeInteger"),limit-2);
            assertArrayEquals(new int[]{limit-4,limit-5},PortalShaderGpu.unusedUnits(program));
        } finally { glUseProgram(0);glDeleteProgram(program); }
    }

    @Test void absentWorldDisablesPreviouslyPublishedCount() {
        int program=program(2);
        try {
            int count=glGetUniformLocation(program,"ipSunCount");glUniform1i(count,4);
            try(var ignored=PortalShaderGpu.bind(null,Vec3.ZERO)) {
                assertEquals(0,glGetUniformi(program,count));
            }
        } finally { glUseProgram(0);glDeleteProgram(program); }
    }

    @Test void zeroRegionDrawUsesDistinctCompleteSamplersAndPreservesTheNativePixel() {
        int program=program("""
            #version 330 core
            uniform int ipSunCount;
            uniform int ipSunPortalView;
            uniform sampler3D ipSunAtlas;
            uniform sampler2DArray ipSunSourceShadow;
            uniform sampler2D host;
            out vec4 color;
            void main() {
                color=texture(host,vec2(.5));
                if(ipSunCount>0 || ipSunPortalView>0)
                    color+=texture(ipSunAtlas,vec3(.5))+texture(ipSunSourceShadow,vec3(.5,.5,0));
            }
            """);
        int host=glGenTextures(),vao=glGenVertexArrays();
        try {
            glBindVertexArray(vao);glViewport(0,0,16,16);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,host);
            var pixel=BufferUtils.createByteBuffer(4).put(new byte[]{64,(byte)128,(byte)192,(byte)255});pixel.flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glUniform1i(glGetUniformLocation(program,"host"),0);
            glUniform1i(glGetUniformLocation(program,"ipSunCount"),0);
            glUniform1i(glGetUniformLocation(program,"ipSunPortalView"),0);
            // Uniform control flow does not remove sampler-type validation from an actual GL draw.
            glDrawArrays(GL_TRIANGLES,0,3);
            assertEquals(GL_INVALID_OPERATION,glGetError());
            try(var ignored=PortalShaderGpu.bind(null,Vec3.ZERO)) {
                int cellUnit=glGetUniformi(program,glGetUniformLocation(program,"ipSunAtlas"));
                int shadowUnit=glGetUniformi(program,glGetUniformLocation(program,"ipSunSourceShadow"));
                assertNotEquals(0,cellUnit);assertNotEquals(0,shadowUnit);assertNotEquals(cellUnit,shadowUnit);
                assertEquals(0,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
                assertEquals(0,glGetUniformi(program,glGetUniformLocation(program,"ipSunPortalView")));
                glDrawArrays(GL_TRIANGLES,0,3);assertEquals(GL_NO_ERROR,glGetError());
                var result=BufferUtils.createByteBuffer(4);glReadPixels(8,8,1,1,GL_RGBA,GL_UNSIGNED_BYTE,result);
                assertEquals(64,Byte.toUnsignedInt(result.get(0)),1);
                assertEquals(128,Byte.toUnsignedInt(result.get(1)),1);
                assertEquals(192,Byte.toUnsignedInt(result.get(2)),1);
                assertEquals(GL_TEXTURE0,glGetInteger(GL_ACTIVE_TEXTURE));assertEquals(host,glGetInteger(GL_TEXTURE_BINDING_2D));
            }
        } finally {
            glBindVertexArray(0);glDeleteVertexArrays(vao);glDeleteTextures(host);glUseProgram(0);glDeleteProgram(program);
        }
    }

    @Test void fogOnlyProgramBindsWithExactlyOneFreeUnitAndNoShadowSampler() {
        int limit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        StringBuilder fragment=new StringBuilder("#version 330 core\nuniform int ipSunCount;uniform sampler3D ipSunAtlas;uniform sampler2D nativeImages[")
            .append(limit-1).append("];out vec4 color;void main(){color=texture(ipSunAtlas,vec3(.5))*float(ipSunCount);");
        for(int i=0;i<limit-1;i++) fragment.append("color+=texture(nativeImages[").append(i).append("],vec2(.5));");
        int program=program(fragment.append('}').toString());
        int previousTexture=glGenTextures(),previousSampler=glGenSamplers();
        try(var atlas=new PortalShaderGpu.Atlas()) {
            for(int i=0;i<limit-1;i++) glUniform1i(glGetUniformLocation(program,"nativeImages["+i+"]"),i);
            assertEquals(-1,glGetUniformLocation(program,"ipSunSourceShadow"));
            assertNull(PortalShaderGpu.unusedUnits(program),"A second free unit does not exist");
            glActiveTexture(GL_TEXTURE0+limit-1);glBindTexture(GL_TEXTURE_3D,previousTexture);glBindSampler(limit-1,previousSampler);
            glActiveTexture(GL_TEXTURE2);
            try(var ignored=PortalShaderGpu.bindAtlas(program,atlas,List.of(region(.125f,255)),()->{})) {
                assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));
                assertEquals(1,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
                assertEquals(limit-1,glGetUniformi(program,glGetUniformLocation(program,"ipSunAtlas")));
                glActiveTexture(GL_TEXTURE0+limit-1);assertEquals(atlas.texture,glGetInteger(GL_TEXTURE_BINDING_3D));
                glActiveTexture(GL_TEXTURE2);
            }
            assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));
            glActiveTexture(GL_TEXTURE0+limit-1);
            assertEquals(previousTexture,glGetInteger(GL_TEXTURE_BINDING_3D));assertEquals(previousSampler,glGetInteger(GL_SAMPLER_BINDING));
        } finally {
            glBindSampler(limit-1,0);glDeleteSamplers(previousSampler);glDeleteTextures(previousTexture);
            glUseProgram(0);glDeleteProgram(program);
        }
    }

    @Test void restoresUnitsSamplersActiveTextureAndHostileUploadStateOnSuccessAndFailure() {
        int program=program(2),limit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        int unitA=limit-1,unitB=limit-2;
        int old3d=glGenTextures(),oldArray=glGenTextures(),samplerA=glGenSamplers(),samplerB=glGenSamplers(),pbo=glGenBuffers();
        try(var atlas=new PortalShaderGpu.Atlas()) {
            glActiveTexture(GL_TEXTURE0+unitA);glBindTexture(GL_TEXTURE_3D,old3d);glBindSampler(unitA,samplerA);
            glActiveTexture(GL_TEXTURE0+unitB);glBindTexture(GL_TEXTURE_2D_ARRAY,oldArray);glBindSampler(unitB,samplerB);
            glActiveTexture(GL_TEXTURE2);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,pbo);glBufferData(GL_PIXEL_UNPACK_BUFFER,16,GL_STATIC_DRAW);
            int[] hostile={8,41,43,2,3,4,GL_TRUE};
            for(int i=0;i<UNPACK_KEYS.length;i++) glPixelStorei(UNPACK_KEYS[i],hostile[i]);
            var regions=List.of(region(.125f,255));
            try(var ignored=PortalShaderGpu.bindAtlas(program,atlas,regions,()->{})) {
                assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));
                assertEquals(1,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
                assertEquals(unitA,glGetUniformi(program,glGetUniformLocation(program,"ipSunAtlas")));
                assertEquals(unitB,glGetUniformi(program,glGetUniformLocation(program,"ipSunSourceShadow")));
                glActiveTexture(GL_TEXTURE0+unitA);assertEquals(atlas.texture,glGetInteger(GL_TEXTURE_BINDING_3D));assertEquals(0,glGetInteger(GL_SAMPLER_BINDING));
                glActiveTexture(GL_TEXTURE0+unitB);assertEquals(atlas.shadowTexture,glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY));assertEquals(0,glGetInteger(GL_SAMPLER_BINDING));
                glActiveTexture(GL_TEXTURE2);
            }
            assertBindings(unitA,unitB,old3d,oldArray,samplerA,samplerB,pbo,hostile);
            assertThrows(IllegalStateException.class,()->PortalShaderGpu.bindAtlas(program,atlas,regions,()->{throw new IllegalStateException("source metadata failed");}));
            assertEquals(0,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
            assertBindings(unitA,unitB,old3d,oldArray,samplerA,samplerB,pbo,hostile);
            assertThrows(IllegalArgumentException.class,()->PortalShaderGpu.bindAtlas(program,atlas,List.of(region(new float[3],new byte[1024])),()->{}));
            assertEquals(0,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
            assertBindings(unitA,unitB,old3d,oldArray,samplerA,samplerB,pbo,hostile);
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glDeleteBuffers(pbo);
            glBindSampler(unitA,0);glBindSampler(unitB,0);glDeleteSamplers(samplerA);glDeleteSamplers(samplerB);
            glDeleteTextures(old3d);glDeleteTextures(oldArray);glUseProgram(0);glDeleteProgram(program);
        }
    }

    @Test void fourRegionsDeduplicateOneOwnedDepthAndRestoreSourceTextureAndSampler() {
        int program=program("""
            #version 330 core
            uniform int ipSunCount;
            uniform sampler3D ipSunAtlas;
            uniform sampler2DArray ipSunSourceShadow;
            uniform sampler2DShadow ipSunDepth0,ipSunDepth1,ipSunDepth2,ipSunDepth3;
            uniform int ipSunDepthValid[4];
            out vec4 color;
            void main(){color=texture(ipSunAtlas,vec3(.5))+texture(ipSunSourceShadow,vec3(.5,.5,0));
                color+=vec4(texture(ipSunDepth0,vec3(.5))*float(ipSunDepthValid[0])+
                    texture(ipSunDepth1,vec3(.5))*float(ipSunDepthValid[1])+
                    texture(ipSunDepth2,vec3(.5))*float(ipSunDepthValid[2])+
                    texture(ipSunDepth3,vec3(.5))*float(ipSunDepthValid[3]));color*=float(ipSunCount);}
            """);
        int nativeDepth=glGenTextures(),previous=glGenTextures(),previousSampler=glGenSamplers();
        var store=new PortalSourceShadow.Store(4,64L*1024*1024);
        int unit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)-3;
        try(var atlas=new PortalShaderGpu.Atlas()) {
            glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,nativeDepth);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT24,1,1,0,GL_DEPTH_COMPONENT,GL_FLOAT,new float[]{1});
            var identity=new org.joml.Matrix4f();
            var captured=store.capture(new Object(),new Object(),new PortalSourceShadow.Capture(nativeDepth,1,Vec3.ZERO,
                identity,identity,96,1,.3f,100,100),1000);
            assertNotNull(captured);
            glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,previous);glBindSampler(unit,previousSampler);
            glActiveTexture(GL_TEXTURE3);
            var regions=java.util.Collections.nCopies(4,region(.125f,255));
            var captures=java.util.Collections.nCopies(4,captured);
            try(var ignored=PortalShaderGpu.bindAtlas(program,atlas,regions,captures,()->{})) {
                for(int i=0;i<4;i++) {
                    assertEquals(unit,glGetUniformi(program,glGetUniformLocation(program,"ipSunDepth"+i)));
                    assertEquals(1,glGetUniformi(program,glGetUniformLocation(program,"ipSunDepthValid["+i+"]")));
                }
                glActiveTexture(GL_TEXTURE0+unit);
                assertEquals(captured.texture(),glGetInteger(GL_TEXTURE_BINDING_2D));
                assertEquals(atlas.depthSampler,glGetInteger(GL_SAMPLER_BINDING));
                assertEquals(GL_COMPARE_REF_TO_TEXTURE,glGetSamplerParameteri(atlas.depthSampler,GL_TEXTURE_COMPARE_MODE));
                assertEquals(GL_LINEAR,glGetSamplerParameteri(atlas.depthSampler,GL_TEXTURE_MIN_FILTER));
                assertEquals(GL_NONE,glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE));
                glActiveTexture(GL_TEXTURE3);
            }
            assertEquals(GL_TEXTURE3,glGetInteger(GL_ACTIVE_TEXTURE));glActiveTexture(GL_TEXTURE0+unit);
            assertEquals(previous,glGetInteger(GL_TEXTURE_BINDING_2D));assertEquals(previousSampler,glGetInteger(GL_SAMPLER_BINDING));
            glActiveTexture(GL_TEXTURE3);
            assertThrows(IllegalStateException.class,()->PortalShaderGpu.bindAtlas(program,atlas,regions,captures,()->{throw new IllegalStateException("metadata");}));
            assertEquals(0,glGetUniformi(program,glGetUniformLocation(program,"ipSunCount")));
            glActiveTexture(GL_TEXTURE0+unit);assertEquals(previous,glGetInteger(GL_TEXTURE_BINDING_2D));assertEquals(previousSampler,glGetInteger(GL_SAMPLER_BINDING));
        } finally {
            store.clear();glBindSampler(unit,0);glDeleteSamplers(previousSampler);glDeleteTextures(previous);glDeleteTextures(nativeDepth);
            glUseProgram(0);glDeleteProgram(program);
        }
    }

    private static void assertBindings(int a,int b,int texture,int array,int samplerA,int samplerB,int pbo,int[] unpack) {
        assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(pbo,glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING));
        for(int i=0;i<UNPACK_KEYS.length;i++) assertEquals(unpack[i],glGetInteger(UNPACK_KEYS[i]));
        glActiveTexture(GL_TEXTURE0+a);assertEquals(texture,glGetInteger(GL_TEXTURE_BINDING_3D));assertEquals(samplerA,glGetInteger(GL_SAMPLER_BINDING));
        glActiveTexture(GL_TEXTURE0+b);assertEquals(array,glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY));assertEquals(samplerB,glGetInteger(GL_SAMPLER_BINDING));
        glActiveTexture(GL_TEXTURE2);
    }
}
