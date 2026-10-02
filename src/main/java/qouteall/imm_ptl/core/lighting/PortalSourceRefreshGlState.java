package qouteall.imm_ptl.core.lighting;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL33.*;

/** Scoped native bindings around a complete offscreen render, before the primary pass starts. */
public final class PortalSourceRefreshGlState implements AutoCloseable {
    private final GlStateBackup cached = new GlStateBackup();
    private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
        program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING),
        arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING), active = glGetInteger(GL_ACTIVE_TEXTURE),
        renderBuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
    private final int[] viewport = new int[4], scissor = new int[4];
    private final float[] clearColor = new float[4];
    private final double clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
    private final int clearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE), frontFace = glGetInteger(GL_FRONT_FACE), cullFace = glGetInteger(GL_CULL_FACE_MODE);
    private final boolean stencil = glIsEnabled(GL_STENCIL_TEST), clip = glIsEnabled(GL_CLIP_DISTANCE0);
    private final int equationRgb = glGetInteger(GL_BLEND_EQUATION_RGB), equationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final int[][] textures;
    private final int[] samplers;
    private final int[] shaderTextures = new int[12];
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix()), textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
    private final com.mojang.blaze3d.vertex.VertexSorting sorting = RenderSystem.getVertexSorting();
    private final net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
    private final float[] shaderColor = RenderSystem.getShaderColor().clone(), fogColor = RenderSystem.getShaderFogColor().clone();
    private final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd(), lineWidth = RenderSystem.getShaderLineWidth();
    private final com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();

    public PortalSourceRefreshGlState() {
        RenderSystem.backupGlState(cached);
        glGetIntegerv(GL_VIEWPORT, viewport); glGetIntegerv(GL_SCISSOR_BOX, scissor); glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        int units = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        textures = new int[units][5]; samplers = new int[units];
        for (int i = 0; i < units; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            textures[i][0] = glGetInteger(GL_TEXTURE_BINDING_2D);
            textures[i][1] = glGetInteger(GL_TEXTURE_BINDING_3D);
            textures[i][2] = glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP);
            textures[i][3] = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
            textures[i][4] = glGetInteger(GL_TEXTURE_BINDING_1D);
            samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(active);
        for (int i = 0; i < shaderTextures.length; i++) shaderTextures[i] = RenderSystem.getShaderTexture(i);
    }

    @Override public void close() {
        RenderSystem.restoreGlState(cached);
        RenderSystem.setProjectionMatrix(projection, sorting); RenderSystem.setTextureMatrix(textureMatrix);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd); RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.lineWidth(lineWidth);
        for (int i = 0; i < shaderTextures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            if (i < GlStateManager.TEXTURE_COUNT) {
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                // External Iris code can have changed GL without updating Minecraft's cache.
                GlStateManager._bindTexture(0); glBindTexture(GL_TEXTURE_2D, 0); GlStateManager._bindTexture(textures[i][0]);
            } else { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i][0]); }
            glBindTexture(GL_TEXTURE_3D, textures[i][1]); glBindTexture(GL_TEXTURE_CUBE_MAP, textures[i][2]);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textures[i][3]); glBindTexture(GL_TEXTURE_1D, textures[i][4]);
            glBindSampler(i, samplers[i]);
        }
        // Synchronize the cached selector before putting the actual selector back.
        GlStateManager._activeTexture(GL_TEXTURE0); GlStateManager._activeTexture(active);
        glActiveTexture(active);
        glUseProgram(program); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        glBindRenderbuffer(GL_RENDERBUFFER, renderBuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]); glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]); glClearDepth(clearDepth); glClearStencil(clearStencil);
        glFrontFace(frontFace); glCullFace(cullFace); glBlendEquationSeparate(equationRgb, equationAlpha);
        set(GL_STENCIL_TEST, stencil); set(GL_CLIP_DISTANCE0, clip);
    }
    private static void set(int capability, boolean enabled) { if (enabled) glEnable(capability); else glDisable(capability); }
}
