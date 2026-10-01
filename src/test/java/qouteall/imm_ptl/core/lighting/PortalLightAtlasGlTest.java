package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Runs the production upload path in a hidden context; no Minecraft world is created. */
@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
class PortalLightAtlasGlTest {
    private static long window;
    private static final int[] UNPACK_KEYS={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_IMAGE_HEIGHT,
        GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES};

    @BeforeAll static void context() {
        assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        window=glfwCreateWindow(16,16,"Portal atlas upload test",0,0);
        assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();
    }

    @AfterAll static void end() {
        GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();
    }

    @AfterEach void reset() {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
        for(int key:UNPACK_KEYS) glPixelStorei(key,key==GL_UNPACK_ALIGNMENT?4:0);
        glBindSampler(2,0);glActiveTexture(GL_TEXTURE0);
        assertEquals(GL_NO_ERROR,glGetError());
    }

    private static PortalLighting.Region region(int origin,float red,float green,float blue) {
        var min=new PortalLightField.Pos(origin,40,-90);
        return new PortalLighting.Region(null,min,Map.of(min,new float[]{red,green,blue}));
    }

    private static float[] texel(PortalLightGpu.Atlas atlas,int x,int y,int z) {
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D);
        try {
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            var pixels=BufferUtils.createFloatBuffer(32*32*128*4);
            glGetTexImage(GL_TEXTURE_3D,0,GL_RGBA,GL_FLOAT,pixels);
            int offset=((z*32+y)*32+x)*4;
            return new float[]{pixels.get(offset),pixels.get(offset+1),pixels.get(offset+2),pixels.get(offset+3)};
        } finally { glBindTexture(GL_TEXTURE_3D,previous); }
    }

    private static void mark(PortalLightGpu.Atlas atlas,int z) {
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D);
        try {
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            var marker=BufferUtils.createFloatBuffer(4).put(new float[]{.75f,.5f,.25f,1});marker.flip();
            glTexSubImage3D(GL_TEXTURE_3D,0,0,0,z,1,1,1,GL_RGBA,GL_FLOAT,marker);
        } finally { glBindTexture(GL_TEXTURE_3D,previous); }
    }

    @Test void allocatesFixedStorageAndPreservesSignedOffsetsAndOccupancy() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            assertEquals(1,atlas.update(1,List.of(region(10,-.25f,.5f,.75f))));
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_WIDTH));
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_HEIGHT));
            assertEquals(128,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_DEPTH));
            assertEquals(GL_RGBA16F,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_INTERNAL_FORMAT));
            assertArrayEquals(new float[]{-.25f,.5f,.75f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[4],texel(atlas,1,0,0));
        }
    }

    @Test void unchangedRegionDoesNotUploadEvenWhenTheDimensionRevisionChanges() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,-.25f,.5f,.75f);
            atlas.update(1,List.of(a));mark(atlas,0);
            assertEquals(0,atlas.update(1,List.of(a)));
            assertEquals(0,atlas.update(2,List.of(a)));
            // A GPU marker absent from the CPU region proves no redundant upload occurred.
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,0));
        }
    }

    @Test void changingOneRegionDoesNotReallocateOrOverwriteAnotherSlot() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,-.25f,.5f,.75f);var b=region(50,.1f,.2f,.3f);
            assertEquals(2,atlas.update(1,List.of(a,b)));mark(atlas,0);
            assertEquals(1,atlas.update(2,List.of(a,region(50,.4f,.5f,.6f))));
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,0));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,32),.001f);
        }
    }

    @Test void reorderingRegionsUpdatesTheirAtlasSlots() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            assertEquals(2,atlas.update(2,List.of(b,a)));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,32),.001f);
        }
    }

    @Test void removalsClearOldSlotsAndEmptyFieldsCanBeRepopulated() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            assertEquals(2,atlas.update(2,List.of(b)));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[4],texel(atlas,0,0,32));
            assertEquals(1,atlas.update(3,List.of()));
            assertArrayEquals(new float[4],texel(atlas,0,0,0));
            assertEquals(0,atlas.update(4,List.of()));
            assertEquals(1,atlas.update(5,List.of(a)));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[4],texel(atlas,0,0,32));
        }
    }

    @Test void replacingSparseFieldClearsCellsNoLongerPresent() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var second=a.min().add(1,0,0);
            var both=new PortalLighting.Region(null,a.min(),Map.of(a.min(),new float[]{.1f,.2f,.3f},second,new float[]{.4f,.5f,.6f}));
            atlas.update(1,List.of(both));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,1,0,0),.001f);
            assertEquals(1,atlas.update(2,List.of(a)));
            assertArrayEquals(new float[4],texel(atlas,1,0,0));
        }
    }

    @Test void independentWorldAtlasesDoNotShareRevisionOrTextureContents() {
        try(var overworld=new PortalLightGpu.Atlas();var nether=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(10,-.2f,-.1f,.4f);
            overworld.update(5,List.of(a));nether.update(5,List.of(b));
            assertNotEquals(overworld.texture,nether.texture);
            nether.update(6,List.of(region(10,.6f,.7f,.8f)));
            assertEquals(0,overworld.update(5,List.of(a)));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(overworld,0,0,0),.001f);
            assertArrayEquals(new float[]{.6f,.7f,.8f,1},texel(nether,0,0,0),.001f);
        }
    }

    @Test void partialFailureRepairsAllVisibleSlotsEvenWhenReturningToThePreviousRevision() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            var bad=new PortalLighting.Region(null,new PortalLightField.Pos(0,0,0),
                Map.of(new PortalLightField.Pos(32,0,0),new float[]{1,1,1}));
            assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(region(10,.8f,.8f,.8f),bad)));
            assertEquals(2,atlas.update(1,List.of(a,b)));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,32),.001f);
            assertEquals(0,atlas.update(1,List.of(a,b)));
        }
    }

    @Test void uploadAndFailureRestoreUnpackBufferPixelStoreTextureSamplerAndActiveUnit() {
        int other=glGenTextures(),buffer=glGenBuffers(),sampler=glGenSamplers();
        glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_3D,other);glBindSampler(2,sampler);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,buffer);glBufferData(GL_PIXEL_UNPACK_BUFFER,4096,GL_STREAM_DRAW);
        int[] hostile={8,41,43,2,3,4,GL_TRUE};
        for(int i=0;i<UNPACK_KEYS.length;i++) glPixelStorei(UNPACK_KEYS[i],hostile[i]);
        try(var atlas=new PortalLightGpu.Atlas()) {
            atlas.update(1,List.of(region(10,.1f,.2f,.3f)));
            assertRestored(other,buffer,sampler,hostile);
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            var bad=new PortalLighting.Region(null,new PortalLightField.Pos(0,0,0),
                Map.of(new PortalLightField.Pos(32,0,0),new float[]{1,1,1}));
            assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(bad)));
            assertRestored(other,buffer,sampler,hostile);
            // A failed publication must not suppress a valid retry at that revision.
            assertEquals(1,atlas.update(2,List.of(region(10,.4f,.5f,.6f))));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertRestored(other,buffer,sampler,hostile);
        } finally { glDeleteTextures(other);glDeleteBuffers(buffer);glDeleteSamplers(sampler); }
    }

    private static void assertRestored(int texture,int buffer,int sampler,int[] unpack) {
        assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(texture,glGetInteger(GL_TEXTURE_BINDING_3D));
        assertEquals(buffer,glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING));
        assertEquals(sampler,glGetInteger(GL_SAMPLER_BINDING));
        for(int i=0;i<UNPACK_KEYS.length;i++) assertEquals(unpack[i],glGetInteger(UNPACK_KEYS[i]));
        assertEquals(GL_NO_ERROR,glGetError());
    }
}
