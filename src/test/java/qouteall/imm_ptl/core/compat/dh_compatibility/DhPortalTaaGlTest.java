package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

@EnabledIfSystemProperty(named="ipsable.glTests", matches="true")
class DhPortalTaaGlTest {
    private static long window;
    private DhPortalTaaPipeline pipeline;
    private int color, depth, fbo;
    private static final int SIZE = 8;
    @BeforeAll static void open() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "DH portal TAA regression", 0, 0); assertNotEquals(0, window);
        glfwMakeContextCurrent(window); GL.createCapabilities();
        System.out.println("Portal TAA GPU: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
    }
    @AfterAll static void close() { GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate(); }
    @BeforeEach void setup() {
        pipeline = new DhPortalTaaPipeline(); glActiveTexture(GL_TEXTURE0); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_FRAMEBUFFER_SRGB);
        glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glColorMask(true,true,true,true);
        color = glGenTextures(); depth = glGenTextures(); fbo = glGenFramebuffers();
        glBindTexture(GL_TEXTURE_2D, color); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,SIZE,SIZE,0,GL_RGBA,GL_FLOAT,0L);
        filters();
        glBindTexture(GL_TEXTURE_2D, depth);
        float[] depths = new float[SIZE*SIZE]; Arrays.fill(depths,.5f);
        glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,SIZE,SIZE,0,GL_DEPTH_COMPONENT,GL_FLOAT,depths); filters();
        glBindFramebuffer(GL_FRAMEBUFFER,fbo); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
    }
    private void filters() { glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST); }
    @AfterEach void cleanup() {
        pipeline.close(); glDeleteFramebuffers(fbo); glDeleteTextures(color); glDeleteTextures(depth);
        assertEquals(GL_NO_ERROR, glGetError());
    }
    private void input(float centre) {
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,color);
        float[] pixels = new float[SIZE*SIZE*4];
        for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            int i=(y*SIZE+x)*4; float c=((x+y)&1)==0?0:1;
            if(x==4 && y==4) c=centre;
            pixels[i]=pixels[i+1]=pixels[i+2]=c; pixels[i+3]=1;
        }
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL_RGBA,GL_FLOAT,pixels);
    }
    private DhPortalTaaPipeline.View prepare(Object key,int frame, boolean zero) {
        return pipeline.prepare(key,frame,frame*16_000_000L,SIZE,SIZE,new Matrix4f(),new Matrix4f(),new Vector3d(),zero);
    }
    private float render(Object key,int frame,float centre,boolean zero) {
        input(centre); var v=prepare(key,frame,zero);
        pipeline.render(v,color,depth,fbo,new Matrix4f(),new Matrix4f(),new Vector3d());
        // Inspect accumulated history before DH's sharpening; .9 history + .1 current at zero velocity.
        glBindTexture(GL_TEXTURE_2D,v.textures[(frame&1)==0?1:0]);
        float[] pixels=new float[SIZE*SIZE*4]; glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_FLOAT,pixels);
        return pixels[(4*SIZE+4)*4];
    }
    @Test void installedTaaAccumulatesSamplesAndConvergesForBothDepthRanges() {
        for(boolean zero:new boolean[]{false,true}) {
            Object key=List.of("overworld",zero);
            assertEquals(.2,render(key,0,.2f,zero),.003);
            assertEquals(.26,render(key,1,.8f,zero),.004);
            double min=1,max=0;
            for(int frame=2;frame<24;frame++) {
                float pixel=render(key,frame,(frame&1)==0?.2f:.8f,zero);
                if(frame>15){min=Math.min(min,pixel);max=Math.max(max,pixel);}
            }
            assertTrue(max-min<.12,"Temporal variation must be much smaller than raw .6 sample flicker");
        }
    }
    @Test void siblingNestedAndDimensionHistoriesNeverSharePixels() {
        Object a=List.of("nether","overworld","portal-a");
        Object b=List.of("nether","overworld","portal-b");
        Object nested=List.of("nether","overworld","portal-a","portal-b");
        Object reverse=List.of("overworld","nether","portal-a");
        render(a,0,.2f,false); render(b,0,.8f,false); render(nested,0,.4f,false); render(reverse,0,.6f,false);
        assertEquals(.26,render(a,1,.8f,false),.004);
        assertEquals(.74,render(b,1,.2f,false),.004);
        assertEquals(.44,render(nested,1,.8f,false),.004);
        assertEquals(.56,render(reverse,1,.2f,false),.004);
        assertEquals(4,pipeline.viewCount());
    }
    @Test void repeatedSameFrameUsesSamePriorOutputAndPhase() {
        render("a",0,.2f,false);
        assertEquals(.26,render("a",1,.8f,false),.004);
        assertEquals(.26,render("a",1,.8f,false),.004);
        assertEquals(1,prepare("a",1,false).history.phase());
    }
    @Test void resizeEvictionAndCleanupDeleteGpuObjectsAndPermitRecreation() {
        var original=prepare("a",0,false); int texture=original.textures[0], framebuffer=original.fbos[0];
        var resized=pipeline.prepare("a",1,16_000_000L,16,16,new Matrix4f(),new Matrix4f(),new Vector3d(),false);
        assertNotSame(original,resized);
        assertArrayEquals(new int[]{0,0},original.textures); assertArrayEquals(new int[]{0,0},original.fbos);
        // Drivers may immediately reuse a deleted name for the resized allocation.
        if(Arrays.stream(resized.textures).noneMatch(id -> id == texture)) assertFalse(glIsTexture(texture));
        if(Arrays.stream(resized.fbos).noneMatch(id -> id == framebuffer)) assertFalse(glIsFramebuffer(framebuffer));
        glBindTexture(GL_TEXTURE_2D,resized.textures[0]);
        assertEquals(16,glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH));
        for(int i=0;i<20;i++) prepare("portal"+i,i+2,false);
        assertTrue(pipeline.viewCount()<=8); assertTrue(pipeline.allocatedBytes()<=256L*1024*1024);
        pipeline.prune(1000,10_000_000_000L); assertEquals(0,pipeline.viewCount());
        var finalView=prepare("new",1001,false); int lastTexture=finalView.textures[0];
        pipeline.close(); assertFalse(glIsTexture(lastTexture)); assertEquals(0,pipeline.viewCount());
        assertEquals(.2,render("restart",0,.2f,false),.003);
    }
    @Test void missedFrameClearsOldHistoryInsteadOfGhosting() {
        render("a",0,.2f,false);
        assertEquals(.8,render("a",3,.8f,false),.003);
    }

    private DhPortalTaaPipeline.Transfer transfer(Object key, int frame) {
        return pipeline.transfer(key,frame,frame*16_000_000L,SIZE,SIZE,new Matrix4f(),new Matrix4f(),new Vector3d(),false);
    }
    private float centrePixel(int texture) {
        glBindTexture(GL_TEXTURE_2D,texture); float[] pixels=new float[SIZE*SIZE*4];
        glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_FLOAT,pixels); return pixels[(4*SIZE+4)*4];
    }
    @Test void crossingSeedsUnsharpenedHistoryAndAvoidsFirstFrameSamplePop() {
        render("crossed-portal",0,.2f,false);
        var donor=transfer("crossed-portal",1); assertNotNull(donor);
        var main=prepare("main",1,false);
        // Model DH's history target: same installed TAA shader and RGB10_A2 format.
        glEnable(GL_SCISSOR_TEST); glScissor(0,0,1,1); glEnable(GL_FRAMEBUFFER_SRGB);
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        assertTrue(DhPortalTaaPipeline.seed(donor,main.fbos[0],SIZE,SIZE));
        assertEquals(read,glGetInteger(GL_READ_FRAMEBUFFER_BINDING)); assertEquals(draw,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertTrue(glIsEnabled(GL_SCISSOR_TEST)); assertTrue(glIsEnabled(GL_FRAMEBUFFER_SRGB));
        assertEquals(.2,centrePixel(main.textures[0]),.003);
        main.history.valid=true; main.history.previousCombined.set(donor.snapshot().combined());
        main.history.previousCamera.set(donor.snapshot().camera());
        input(.8f); pipeline.render(main,color,depth,fbo,new Matrix4f(),new Matrix4f(),new Vector3d());
        assertEquals(.26,centrePixel(main.textures[1]),.004,"Donated history blends instead of flashing raw .8");
        assertEquals(.8,render("reset-control",0,.8f,false),.003);
    }
    @Test void crossingRejectsOtherPathsResizeStalenessAndOverwrittenDonors() {
        render("exact",0,.2f,false);
        assertNull(transfer("sibling",1)); assertNull(transfer("nested",1)); assertNull(transfer("exact",3));
        assertNull(pipeline.transfer("exact",1,16_000_000L,SIZE*2,SIZE,new Matrix4f(),new Matrix4f(),new Vector3d(),false));
        assertNull(pipeline.transfer("exact",1,16_000_000L,SIZE,SIZE,new Matrix4f(),new Matrix4f(),new Vector3d(),true));
        var donor=transfer("exact",1); assertNotNull(donor);
        render("exact",1,.8f,false); input(.7f);
        assertFalse(DhPortalTaaPipeline.seed(donor,fbo,SIZE,SIZE)); assertEquals(0,centrePixel(color),.0001);
        donor=transfer("exact",2); pipeline.close(); input(.7f);
        assertFalse(DhPortalTaaPipeline.seed(donor,fbo,SIZE,SIZE)); assertEquals(0,centrePixel(color),.0001);
    }

    @Test void backingIntoPortalPreservesMainImageEvenAfterDhOverwritesItsTargets() {
        render("old-main",0,.2f,false); var old=prepare("old-main",0,false);
        var snapshot=new DhTaaHistory.Snapshot(0,0,7,new Matrix4f(),new Matrix4f(),new Vector3d());
        glEnable(GL_SCISSOR_TEST); glScissor(0,0,1,1); glEnable(GL_FRAMEBUFFER_SRGB);
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        assertTrue(pipeline.capture("linked-return",snapshot,old.fbos[1],SIZE,SIZE,false));
        assertEquals(read,glGetInteger(GL_READ_FRAMEBUFFER_BINDING));assertEquals(draw,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertTrue(glIsEnabled(GL_SCISSOR_TEST));assertTrue(glIsEnabled(GL_FRAMEBUFFER_SRGB));
        glDisable(GL_SCISSOR_TEST); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,old.fbos[1]);
        glClearBufferfv(GL_COLOR,0,new float[]{1,1,1,1});
        assertEquals(.8,render("unrelated-portal",0,.8f,false),.003);
        var returned=prepare("linked-return",1,false);
        assertTrue(returned.history.valid);assertEquals(0,returned.history.phase());
        assertEquals(.26,render("linked-return",1,.8f,false),.004);
        assertEquals(.26,render("linked-return",1,.8f,false),.004,"Repeated uploads keep the donated prior frame");
        // The independently accumulated portal can subsequently hand history back to main again.
        var donor=transfer("linked-return",2); assertNotNull(donor);
        assertTrue(DhPortalTaaPipeline.seed(donor,fbo,SIZE,SIZE));assertEquals(.26,centrePixel(color),.004);
    }

    @Test void returnCaptureKeepsPoolBoundsAndRejectsInvalidSourcesOrIncompatibleNextViews() {
        input(.2f);var snapshot=new DhTaaHistory.Snapshot(0,0,3,new Matrix4f(),new Matrix4f(),new Vector3d());
        assertFalse(pipeline.capture("invalid",snapshot,0,SIZE,SIZE,false));assertEquals(0,pipeline.viewCount());
        assertTrue(pipeline.capture("return",snapshot,fbo,SIZE,SIZE,false));
        var resized=pipeline.prepare("return",1,16_000_000L,16,16,new Matrix4f(),new Matrix4f(),new Vector3d(),false);
        assertFalse(resized.history.valid);assertEquals(0,resized.history.phase());
        assertTrue(pipeline.capture("return",snapshot,fbo,SIZE,SIZE,false));
        var jumped=pipeline.prepare("return",1,16_000_000L,SIZE,SIZE,new Matrix4f(),new Matrix4f(),new Vector3d(20,0,0),false);
        assertFalse(jumped.history.valid);
        for(int i=1;i<20;i++) {
            var next=new DhTaaHistory.Snapshot(i,i*16_000_000L,3,new Matrix4f(),new Matrix4f(),new Vector3d());
            assertTrue(pipeline.capture("return-"+i,next,fbo,SIZE,SIZE,false));
        }
        assertTrue(pipeline.viewCount()<=8);assertTrue(pipeline.allocatedBytes()<=256L*1024*1024);
        pipeline.close();assertEquals(0,pipeline.viewCount());
    }
    @Test void restoresHostileGlStateAndDoesNotModifyUnrelatedTargets() {
        input(.2f); var v=prepare("a",0,false);
        int unrelated=glGenTextures(); glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D,unrelated);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,new float[]{.1f,.3f,.7f,1});
        int sampler=glGenSamplers(); glBindSampler(2,sampler);
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D,unrelated);
        glEnable(GL_SCISSOR_TEST); glEnable(GL_STENCIL_TEST); glEnable(GL_BLEND); glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE); glEnable(GL_FRAMEBUFFER_SRGB); glEnable(GL_CLIP_DISTANCE0);
        glScissor(0,0,1,1); glColorMask(false,true,false,true); glViewport(2,3,4,5);
        glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT,GL_FUNC_SUBTRACT);
        glBlendFuncSeparate(GL_ONE,GL_ZERO,GL_ZERO,GL_ONE);
        pipeline.render(v,color,depth,fbo,new Matrix4f(),new Matrix4f(),new Vector3d());
        assertEquals(GL_TEXTURE5,glGetInteger(GL_ACTIVE_TEXTURE)); assertEquals(unrelated,glGetInteger(GL_TEXTURE_BINDING_2D));
        for(int cap:new int[]{GL_SCISSOR_TEST,GL_STENCIL_TEST,GL_BLEND,GL_DEPTH_TEST,GL_CULL_FACE,GL_FRAMEBUFFER_SRGB,GL_CLIP_DISTANCE0}) assertTrue(glIsEnabled(cap));
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);assertArrayEquals(new int[]{2,3,4,5},viewport);
        assertEquals(GL_FUNC_REVERSE_SUBTRACT,glGetInteger(GL_BLEND_EQUATION_RGB));
        assertEquals(GL_FUNC_SUBTRACT,glGetInteger(GL_BLEND_EQUATION_ALPHA));
        assertEquals(fbo,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        float[] pixel=new float[4];glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_FLOAT,pixel);assertArrayEquals(new float[]{.1f,.3f,.7f,1},pixel);
        glActiveTexture(GL_TEXTURE2);assertEquals(sampler,glGetInteger(GL_SAMPLER_BINDING));
        glBindSampler(2,0);glDeleteSamplers(sampler);glDeleteTextures(unrelated);glDisable(GL_CLIP_DISTANCE0);
    }
}
