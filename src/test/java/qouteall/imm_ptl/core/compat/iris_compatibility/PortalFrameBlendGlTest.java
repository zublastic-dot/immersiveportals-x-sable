package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

@EnabledIfSystemProperty(named="ipsable.glTests",matches="true")
class PortalFrameBlendGlTest {
    static long window;
    int fbo,color,depth;
    PortalFrameBlend blend;
    @BeforeAll static void init(){assertTrue(glfwInit());glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);window=glfwCreateWindow(128,128,"Portal seam test",0,0);assertNotEquals(0,window);glfwMakeContextCurrent(window);GL.createCapabilities();}
    @AfterAll static void end(){GL.setCapabilities(null);glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();}
    @BeforeEach void prepare(){
        blend=new PortalFrameBlend();fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        color=glGenTextures();glBindTexture(GL_TEXTURE_2D,color);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,128,128,0,GL_RGBA,GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
        depth=glGenTextures();glBindTexture(GL_TEXTURE_2D,depth);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH24_STENCIL8,128,128,0,GL_DEPTH_STENCIL,GL_UNSIGNED_INT_24_8,(java.nio.ByteBuffer)null);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_BLEND);glColorMask(true,true,true,true);glDepthMask(true);glStencilMask(255);
        half(0,.08f,.06f,.12f,.5,0);half(64,.2f,.1f,.08f,.5,1);glDisable(GL_SCISSOR_TEST);
    }
    void half(int x,float r,float g,float b,double d,int stencil){glEnable(GL_SCISSOR_TEST);glScissor(x,0,64,128);glClearColor(r,g,b,1);glClearDepth(d);glClearStencil(stencil);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);}
    float[] pixel(int x,int y){glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);float[] p=new float[4];glReadPixels(x,y,1,1,GL_RGBA,GL_FLOAT,p);return p;}
    @AfterEach void cleanup(){blend.clear();glDeleteTextures(color);glDeleteTextures(depth);glDeleteFramebuffers(fbo);glBindFramebuffer(GL_FRAMEBUFFER,0);}
    @Test void blendsBothSidesWithoutChangingDistantPixelsOrAlpha(){
        float[] far=pixel(20,64),left=pixel(63,64),right=pixel(64,64);
        blend.apply(fbo,128,128,new Matrix4f());
        assertArrayEquals(far,pixel(20,64),.005f);
        assertTrue(pixel(63,64)[0]>left[0]);assertTrue(pixel(64,64)[0]<right[0]);
        assertEquals(1,pixel(63,64)[3],.001);assertEquals(GL_NO_ERROR,glGetError());
    }
    @Test void rejectsDepthDiscontinuitiesAndSky(){
        half(64,.2f,.1f,.08f,1,1);glDisable(GL_SCISSOR_TEST);float[] left=pixel(63,64),right=pixel(64,64);
        blend.apply(fbo,128,128,new Matrix4f());assertArrayEquals(left,pixel(63,64),.005f);assertArrayEquals(right,pixel(64,64),.005f);
        half(64,.2f,.1f,.08f,.95,1);glDisable(GL_SCISSOR_TEST);right=pixel(64,64);
        blend.apply(fbo,128,128,new Matrix4f());assertArrayEquals(right,pixel(64,64),.005f);
    }
    @Test void keepsVerticalColorVariationAndRejectsBrightEdges(){
        glEnable(GL_SCISSOR_TEST);glScissor(64,64,64,64);glClearColor(.08f,.2f,.24f,1);glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
        blend.apply(fbo,128,128,new Matrix4f());assertTrue(pixel(63,96)[2]>pixel(63,32)[2]);
        half(64,1,.6f,.1f,.5,1);glDisable(GL_SCISSOR_TEST);float[] left=pixel(63,32);blend.apply(fbo,128,128,new Matrix4f());assertArrayEquals(left,pixel(63,32),.005f);
    }
    @Test void restoresStateAndCanRecreateAfterCleanup(){
        glEnable(GL_SCISSOR_TEST);glScissor(4,5,6,7);glEnable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthMask(true);glColorMask(false,true,false,true);glViewport(3,4,80,90);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,color);
        glStencilFuncSeparate(GL_BACK,GL_EQUAL,3,17);glStencilMaskSeparate(GL_BACK,19);
        blend.apply(fbo,128,128,new Matrix4f());
        assertTrue(glIsEnabled(GL_SCISSOR_TEST));assertTrue(glIsEnabled(GL_BLEND));assertTrue(glIsEnabled(GL_DEPTH_TEST));assertTrue(glGetBoolean(GL_DEPTH_WRITEMASK));
        assertEquals(GL_TEXTURE2,glGetInteger(GL_ACTIVE_TEXTURE));assertEquals(color,glGetInteger(GL_TEXTURE_BINDING_2D));assertEquals(19,glGetInteger(GL_STENCIL_BACK_WRITEMASK));
        assertEquals(color,glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
        blend.clear();blend.apply(fbo,128,128,new Matrix4f());assertEquals(GL_NO_ERROR,glGetError());
    }
    @Test void noPortalMaskLeavesImageUnchangedAndResizeReallocatesSafely(){
        glDisable(GL_SCISSOR_TEST);glStencilMask(255);glClearStencil(0);glClear(GL_STENCIL_BUFFER_BIT);
        float[] before=pixel(63,32);blend.apply(fbo,128,128,new Matrix4f());assertArrayEquals(before,pixel(63,32),.005f);
        glBindTexture(GL_TEXTURE_2D,color);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,64,64,0,GL_RGBA,GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
        glBindTexture(GL_TEXTURE_2D,depth);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH24_STENCIL8,64,64,0,GL_DEPTH_STENCIL,GL_UNSIGNED_INT_24_8,(java.nio.ByteBuffer)null);
        glClearColor(.1f,.2f,.3f,1);glClearDepth(.5);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);
        blend.apply(fbo,64,64,new Matrix4f());assertEquals(.2f,pixel(31,31)[1],.01f);assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));assertEquals(GL_NO_ERROR,glGetError());
    }
}
