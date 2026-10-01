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
    private static final java.nio.FloatBuffer readback=BufferUtils.createFloatBuffer(32*64*256*4);
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
            var pixels=readback.clear();
            glGetTexImage(GL_TEXTURE_3D,0,GL_RGBA,GL_FLOAT,pixels);
            int offset=((z*64+y)*32+x)*4;
            return new float[]{pixels.get(offset),pixels.get(offset+1),pixels.get(offset+2),pixels.get(offset+3)};
        } finally { glBindTexture(GL_TEXTURE_3D,previous); }
    }

    private static void mark(PortalLightGpu.Atlas atlas,int z) { mark(atlas,0,0,z); }

    private static void mark(PortalLightGpu.Atlas atlas,int x,int y,int z) {
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D);
        try {
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            var marker=BufferUtils.createFloatBuffer(4).put(new float[]{.75f,.5f,.25f,1});marker.flip();
            glTexSubImage3D(GL_TEXTURE_3D,0,x,y,z,1,1,1,GL_RGBA,GL_FLOAT,marker);
        } finally { glBindTexture(GL_TEXTURE_3D,previous); }
    }

    @Test void allocatesFixedStorageAndPreservesSignedOffsetsAndOccupancy() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            assertEquals(1,atlas.update(1,List.of(region(10,-.25f,.5f,.75f))));
            glBindTexture(GL_TEXTURE_3D,atlas.texture);
            assertEquals(32,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_WIDTH));
            assertEquals(64,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_HEIGHT));
            assertEquals(256,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_DEPTH));
            assertEquals(GL_RGBA16F,glGetTexLevelParameteri(GL_TEXTURE_3D,0,GL_TEXTURE_INTERNAL_FORMAT));
            assertArrayEquals(new float[]{-.25f,.5f,.75f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{-.25f,.5f,.75f,1},texel(atlas,0,0,128),.001f);
            assertArrayEquals(new float[4],texel(atlas,1,0,0));
            assertArrayEquals(new float[4],texel(atlas,1,0,128));
        }
    }

    @Test void unchangedRegionDoesNotUploadEvenWhenTheDimensionRevisionChanges() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,-.25f,.5f,.75f);
            atlas.update(1,List.of(a));mark(atlas,0);mark(atlas,128);
            assertEquals(0,atlas.update(1,List.of(a)));
            assertEquals(0,atlas.update(2,List.of(a)));
            // A GPU marker absent from the CPU region proves no redundant upload occurred.
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,0));
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,128));
        }
    }

    @Test void changingOneRegionDoesNotReallocateOrOverwriteAnotherSlot() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,-.25f,.5f,.75f);var b=region(50,.1f,.2f,.3f);
            assertEquals(2,atlas.update(1,List.of(a,b)));mark(atlas,0);mark(atlas,128);
            assertEquals(1,atlas.update(2,List.of(a,region(50,.4f,.5f,.6f))));
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,0));
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,128));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,32),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,160),.001f);
        }
    }

    @Test void reorderingRegionsUpdatesTheirAtlasSlots() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            assertEquals(2,atlas.update(2,List.of(b,a)));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,32),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,128),.001f);
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,160),.001f);
        }
    }

    @Test void removalsClearOldSlotsAndEmptyFieldsCanBeRepopulated() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            assertEquals(2,atlas.update(2,List.of(b)));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[4],texel(atlas,0,0,32));
            assertArrayEquals(new float[4],texel(atlas,0,0,160));
            assertEquals(1,atlas.update(3,List.of()));
            assertArrayEquals(new float[4],texel(atlas,0,0,0));
            assertArrayEquals(new float[4],texel(atlas,0,0,128));
            assertEquals(0,atlas.update(4,List.of()));
            assertEquals(1,atlas.update(5,List.of(a)));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[4],texel(atlas,0,0,32));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,128),.001f);
            assertArrayEquals(new float[4],texel(atlas,0,0,160));
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
            assertArrayEquals(new float[4],texel(atlas,1,0,128));
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
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(overworld,0,0,128),.001f);
            assertArrayEquals(new float[]{.6f,.7f,.8f,1},texel(nether,0,0,128),.001f);
        }
    }

    @Test void ambientOnlyPublicationUpdatesItsBankWithoutTouchingAnotherRegion() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.5f,.6f,.7f);var b=region(50,.2f,.3f,.4f);
            var split=new PortalLighting.Region(null,a.min(),a.offsets(),
                Map.of(a.min(),new float[]{-.2f,-.1f,.05f}));
            atlas.update(1,List.of(split,b));mark(atlas,32);mark(atlas,160);
            assertArrayEquals(new float[]{.5f,.6f,.7f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{-.2f,-.1f,.05f,1},texel(atlas,0,0,128),.001f);
            var changed=new PortalLighting.Region(null,a.min(),a.offsets(),
                Map.of(a.min(),new float[]{-.3f,-.15f,.1f}));
            assertEquals(1,atlas.update(2,List.of(changed,b)));
            assertArrayEquals(new float[]{.5f,.6f,.7f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{-.3f,-.15f,.1f,1},texel(atlas,0,0,128),.001f);
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,32));
            assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,160));
        }
    }

    @Test void allFourSlotsKeepDistinctBanksAtTheirLastTexelAndClearTheFinalSlot() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var regions=new java.util.ArrayList<PortalLighting.Region>();
            for(int i=0;i<4;i++) {
                var min=new PortalLightField.Pos(i*40,0,0);var last=min.add(31,31,31);
                regions.add(new PortalLighting.Region(null,min,
                    Map.of(last,new float[]{.1f*(i+1),.5f,.75f}),
                    Map.of(last,new float[]{-.1f*(i+1),-.25f,.05f})));
            }
            assertEquals(4,atlas.update(1,regions));
            for(int i=0;i<4;i++) {
                assertArrayEquals(new float[]{.1f*(i+1),.5f,.75f,1},texel(atlas,31,31,i*32+31),.001f);
                assertArrayEquals(new float[]{-.1f*(i+1),-.25f,.05f,1},texel(atlas,31,31,128+i*32+31),.001f);
                assertArrayEquals(new float[4],texel(atlas,30,31,i*32+31));
                assertArrayEquals(new float[4],texel(atlas,30,31,128+i*32+31));
            }
            assertEquals(1,atlas.update(2,regions.subList(0,3)));
            assertArrayEquals(new float[4],texel(atlas,31,31,127));
            assertArrayEquals(new float[4],texel(atlas,31,31,255));
        }
    }

    @Test void partialFailureRepairsAllVisibleSlotsEvenWhenReturningToThePreviousRevision() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=region(10,.1f,.2f,.3f);var b=region(50,.4f,.5f,.6f);
            atlas.update(1,List.of(a,b));
            // Total bank succeeds before the second bank fails. The first region has
            // already uploaded both banks too, so all visible bank pairs need repair.
            var bad=new PortalLighting.Region(null,b.min(),
                Map.of(b.min(),new float[]{.9f,.9f,.9f}),Map.of(b.min(),new float[]{1,1}));
            assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(region(10,.8f,.8f,.8f),bad)));
            assertEquals(2,atlas.update(1,List.of(a,b)));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,32),.001f);
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,128),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,160),.001f);
            assertEquals(0,atlas.update(1,List.of(a,b)));
        }
    }

    @Test void nonFiniteAmbientOffsetCannotPublishABrokenBankPair() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var good=region(10,.1f,.2f,.3f);
            for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY}) {
                var bad=new PortalLighting.Region(null,good.min(),good.offsets(),
                    Map.of(good.min(),new float[]{invalid,0,0}));
                assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(bad)));
                assertEquals(1,atlas.update(1,List.of(good)));
                assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,128),.001f);
            }
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
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,128),.001f);
            var bad=new PortalLighting.Region(null,new PortalLightField.Pos(0,0,0),
                Map.of(new PortalLightField.Pos(32,0,0),new float[]{1,1,1}));
            assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(bad)));
            assertRestored(other,buffer,sampler,hostile);
            // A failed publication must not suppress a valid retry at that revision.
            assertEquals(1,atlas.update(2,List.of(region(10,.4f,.5f,.6f))));
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{.4f,.5f,.6f,1},texel(atlas,0,0,128),.001f);
            assertRestored(other,buffer,sampler,hostile);
        } finally { glDeleteTextures(other);glDeleteBuffers(buffer);glDeleteSamplers(sampler); }
    }


    private static PortalLightPalette palette(int blue) {
        int[] pixels=new int[256];
        for(int sky=0;sky<16;sky++) for(int block=0;block<16;block++)
            pixels[sky*16+block]=0xff000000|(blue<<16)|((sky*17)<<8)|(block*17);
        return new PortalLightPalette(pixels);
    }

    private static PortalLighting.Region vanillaRegion(int origin,float sky,float block,float weight,int blue) {
        var base=region(origin,.1f,.2f,.3f);
        return new PortalLighting.Region(null,base.min(),base.offsets(),base.ambientOffsets(),
            new PortalLighting.VanillaData(Map.of(base.min(),new float[]{sky,block,weight}),palette(blue),palette(255-blue)));
    }

    private static void assertSlotEmpty(PortalLightGpu.Atlas atlas,int slot) {
        // Read once, then check every channel in all four 32-cube quadrants.
        texel(atlas,0,0,0);
        for(int bank=0;bank<2;bank++) for(int z=0;z<32;z++) for(int y=0;y<64;y++) for(int x=0;x<32;x++) {
            int offset=((((bank*4+slot)*32+z)*64+y)*32+x)*4;
            for(int c=0;c<4;c++) if(readback.get(offset+c)!=0)
                fail("Removed atlas slot retained data at "+x+","+y+","+((bank*4+slot)*32+z));
        }
    }

    @Test void vanillaMetadataAndPalettesKeepCoordinatesOccupancyAndZeroPadding() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var min=new PortalLightField.Pos(10,40,-90);var edge=min.add(31,31,31);
            var offsets=Map.of(min,new float[]{.1f,.2f,.3f},edge,new float[]{-.1f,-.2f,-.3f});
            var metadata=Map.of(min,new float[]{12,9,.375f},edge,new float[]{0,15,0});
            var region=new PortalLighting.Region(null,min,offsets,offsets,
                new PortalLighting.VanillaData(metadata,palette(34),palette(221)));
            assertEquals(1,atlas.update(1,List.of(region)));
            assertArrayEquals(new float[]{12,9,.375f,1},texel(atlas,0,32,0),.001f);
            // Replacement zero is an occupied input cell, not a missing sample.
            assertArrayEquals(new float[]{0,15,0,1},texel(atlas,31,63,31),.001f);
            assertArrayEquals(new float[]{0,0,34/255f,1},texel(atlas,0,32,128),.001f);
            assertArrayEquals(new float[]{1,1,34/255f,1},texel(atlas,15,47,128),.001f);
            assertArrayEquals(new float[]{1,0,221/255f,1},texel(atlas,15,32,129),.001f);
            assertArrayEquals(new float[]{0,1,221/255f,1},texel(atlas,0,47,129),.001f);
            assertArrayEquals(new float[4],texel(atlas,16,32,128));
            assertArrayEquals(new float[4],texel(atlas,0,48,129));
            assertArrayEquals(new float[4],texel(atlas,0,32,130));
            assertArrayEquals(new float[4],texel(atlas,31,63,159));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertArrayEquals(new float[]{-.1f,-.2f,-.3f,1},texel(atlas,31,31,159),.001f);
        }
    }

    @Test void scalarOrPaletteOnlyPublicationUpdatesOneSlotAndReusesTheOther() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=vanillaRegion(10,12,9,.25f,34);var b=vanillaRegion(50,4,6,.75f,51);
            atlas.update(1,List.of(a,b));
            mark(atlas,32);mark(atlas,160);mark(atlas,0,32,32);mark(atlas,0,32,160);mark(atlas,0,32,161);
            var metadataOnly=new PortalLighting.Region(null,a.min(),a.offsets(),a.ambientOffsets(),
                new PortalLighting.VanillaData(Map.of(a.min(),new float[]{13,10,.5f}),a.vanilla().nativePalette(),a.vanilla().referencePalette()));
            assertEquals(1,atlas.update(2,List.of(metadataOnly,b)));
            assertArrayEquals(new float[]{13,10,.5f,1},texel(atlas,0,32,0),.001f);
            var paletteOnly=new PortalLighting.Region(null,a.min(),a.offsets(),a.ambientOffsets(),
                new PortalLighting.VanillaData(metadataOnly.vanilla().cells(),palette(68),palette(187)));
            assertEquals(1,atlas.update(3,List.of(paletteOnly,b)));
            assertArrayEquals(new float[]{13,10,.5f,1},texel(atlas,0,32,0),.001f);
            assertArrayEquals(new float[]{0,0,68/255f,1},texel(atlas,0,32,128),.001f);
            assertArrayEquals(new float[]{0,0,187/255f,1},texel(atlas,0,32,129),.001f);
            assertEquals(0,atlas.update(4,List.of(paletteOnly,b)));
            for(int z:new int[]{32,160}) assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,0,z));
            for(int z:new int[]{32,160,161}) assertArrayEquals(new float[]{.75f,.5f,.25f,1},texel(atlas,0,32,z));
        }
    }

    @Test void allVanillaSlotsReorderClearAndDisableForLegacyRegions() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var regions=new java.util.ArrayList<PortalLighting.Region>();
            for(int i=0;i<4;i++) regions.add(vanillaRegion(i*40,i,15-i,i/4f,17*(i+1)));
            assertEquals(4,atlas.update(1,regions));
            for(int i=0;i<4;i++) {
                assertArrayEquals(new float[]{i,15-i,i/4f,1},texel(atlas,0,32,i*32),.001f);
                assertArrayEquals(new float[]{0,0,17*(i+1)/255f,1},texel(atlas,0,32,128+i*32),.001f);
                assertArrayEquals(new float[]{1,1,(255-17*(i+1))/255f,1},texel(atlas,15,47,129+i*32),.001f);
            }
            var reversed=List.of(regions.get(3),regions.get(2),regions.get(1),regions.get(0));
            assertEquals(4,atlas.update(2,reversed));
            assertArrayEquals(new float[]{3,12,.75f,1},texel(atlas,0,32,0),.001f);
            assertArrayEquals(new float[]{0,15,0,1},texel(atlas,0,32,96),.001f);
            assertArrayEquals(new float[]{0,0,17/255f,1},texel(atlas,0,32,224),.001f);
            assertEquals(3,atlas.update(3,reversed.subList(0,1)));
            for(int i=1;i<4;i++) assertSlotEmpty(atlas,i);
            // A legacy region must clear any former metadata/palettes, not inherit them.
            var legacy=region(120,.1f,.2f,.3f);
            assertEquals(1,atlas.update(4,List.of(legacy)));
            assertArrayEquals(new float[4],texel(atlas,0,32,0));
            assertArrayEquals(new float[4],texel(atlas,0,32,128));
            assertArrayEquals(new float[4],texel(atlas,15,47,129));
            assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,0),.001f);
            assertEquals(1,atlas.update(5,List.of()));assertSlotEmpty(atlas,0);
        }
    }

    @Test void failedUploadRepairsMetadataAndPalettesAlongWithBothOffsetBanks() {
        try(var atlas=new PortalLightGpu.Atlas()) {
            var a=vanillaRegion(10,12,9,.25f,34);var b=vanillaRegion(50,4,6,.75f,51);
            atlas.update(1,List.of(a,b));
            var replacement=vanillaRegion(10,3,2,.5f,102);
            var bad=new PortalLighting.Region(null,b.min(),b.offsets(),Map.of(b.min(),new float[]{1,1}),
                new PortalLighting.VanillaData(Map.of(b.min(),new float[]{15,15,1}),palette(119),palette(136)));
            // First slot has already changed all four quadrants. Second slot has
            // changed total/scalar data before its invalid ambient array fails.
            assertThrows(IllegalArgumentException.class,()->atlas.update(2,List.of(replacement,bad)));
            assertEquals(2,atlas.update(1,List.of(a,b)));
            for(int i=0;i<2;i++) {
                var original=i==0?a:b;
                float[] scalar=original.vanilla().cells().get(original.min());
                assertArrayEquals(new float[]{scalar[0],scalar[1],scalar[2],1},texel(atlas,0,32,i*32),.001f);
                assertArrayEquals(new float[]{0,0,(i==0?34:51)/255f,1},texel(atlas,0,32,128+i*32),.001f);
                assertArrayEquals(new float[]{0,0,(i==0?221:204)/255f,1},texel(atlas,0,32,129+i*32),.001f);
                assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,i*32),.001f);
                assertArrayEquals(new float[]{.1f,.2f,.3f,1},texel(atlas,0,0,128+i*32),.001f);
            }
            assertEquals(0,atlas.update(1,List.of(a,b)));
        }
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
