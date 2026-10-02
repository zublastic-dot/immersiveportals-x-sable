package qouteall.imm_ptl.core.lighting;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="ipsable.glTests", matches="true")
class PortalSourceRefreshGlTest {
    private static long window;
    @BeforeAll static void context() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        window=glfwCreateWindow(16,16,"Auxiliary source state",0,0); assertNotEquals(0,window);
        glfwMakeContextCurrent(window); GL.createCapabilities();
        // JUnit reuses its worker across GL fixtures; RenderSystem allows exactly one owner.
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
    }
    @AfterAll static void end() {
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }
    @Test void fullRenderFailureRestoresCachedCapsNativeSelectorsTargetsAndSamplerBindings() {
        int texture=glGenTextures(), other=glGenTextures(), volume=glGenTextures(), sampler=glGenSamplers();
        int read=glGenFramebuffers(), draw=glGenFramebuffers(), temp=glGenFramebuffers();
        try {
            GlStateManager._activeTexture(GL_TEXTURE3); glActiveTexture(GL_TEXTURE3);
            GlStateManager._bindTexture(texture); glBindTexture(GL_TEXTURE_2D,texture);
            glBindTexture(GL_TEXTURE_3D,volume); glBindSampler(3,sampler);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
            RenderSystem.enableDepthTest(); RenderSystem.depthMask(false); RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ZERO);
            glViewport(2,3,7,9); glScissor(1,4,5,6); glEnable(GL_STENCIL_TEST); glEnable(GL_CLIP_DISTANCE0);
            assertThrows(IllegalArgumentException.class,()-> {
                try (var scope=PortalSourceRefreshPolicy.enter(); var state=new PortalSourceRefreshGlState()) {
                    RenderSystem.disableDepthTest(); RenderSystem.depthMask(true); RenderSystem.disableBlend();
                    glBindFramebuffer(GL_FRAMEBUFFER,temp); glViewport(0,0,16,16); glScissor(0,0,16,16);
                    glDisable(GL_STENCIL_TEST); glDisable(GL_CLIP_DISTANCE0);
                    // Native Iris bindings can bypass Minecraft's cached active selector.
                    glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D,other);
                    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D,other); glBindSampler(3,0);
                    glBindTexture(GL_TEXTURE_3D,0); glActiveTexture(GL_TEXTURE7);
                    throw new IllegalArgumentException("native render failed");
                }
            });
            assertFalse(PortalSourceRefreshPolicy.isRendering());
            assertEquals(GL_TEXTURE3,glGetInteger(GL_ACTIVE_TEXTURE));
            assertEquals(texture,glGetInteger(GL_TEXTURE_BINDING_2D)); assertEquals(volume,glGetInteger(GL_TEXTURE_BINDING_3D));
            assertEquals(sampler,glGetInteger(GL_SAMPLER_BINDING));
            assertEquals(read,glGetInteger(GL_READ_FRAMEBUFFER_BINDING)); assertEquals(draw,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            assertTrue(glIsEnabled(GL_DEPTH_TEST)); assertFalse(glGetBoolean(GL_DEPTH_WRITEMASK));
            assertTrue(glIsEnabled(GL_BLEND)); assertTrue(glIsEnabled(GL_STENCIL_TEST)); assertTrue(glIsEnabled(GL_CLIP_DISTANCE0));
            int[] viewport=new int[4]; glGetIntegerv(GL_VIEWPORT,viewport); assertArrayEquals(new int[]{2,3,7,9},viewport);
            assertEquals(GL_SRC_ALPHA,glGetInteger(GL_BLEND_SRC_RGB)); assertEquals(GL_ONE_MINUS_SRC_ALPHA,glGetInteger(GL_BLEND_DST_RGB));
            // Exercise the cache after restore: both its selector and binding must be correct.
            GlStateManager._activeTexture(GL_TEXTURE3); GlStateManager._bindTexture(texture);
            assertEquals(texture,glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER,0); glBindSampler(3,0); RenderSystem.depthMask(true);
            glDisable(GL_STENCIL_TEST); glDisable(GL_CLIP_DISTANCE0);
            glDeleteFramebuffers(read); glDeleteFramebuffers(draw); glDeleteFramebuffers(temp);
            glDeleteTextures(texture); glDeleteTextures(other); glDeleteTextures(volume); glDeleteSamplers(sampler);
        }
    }
}
