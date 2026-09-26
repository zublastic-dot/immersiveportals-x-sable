package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import qouteall.q_misc_util.Helper;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Some UI integrations replace the main target's depth storage in place, keeping
 * its texture ID. IP's deferred targets must match that actual storage before a
 * depth blit/image copy. Resizing the main target here would discard scene depth.
 */
final class PortalDepthStencilCompatibility {
    private static int reportedAdjustments;

    private PortalDepthStencilCompatibility() {}

    static void prepareCopy(RenderTarget source, RenderTarget destination) {
        RenderSystem.assertOnRenderThread();
        int sourceTexture = source.getDepthTextureId();
        int destinationTexture = destination.getDepthTextureId();
        if (sourceTexture <= 0 || destinationTexture <= 0 || sourceTexture == destinationTexture) {
            return;
        }

        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int oldDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            // Do not cache by texture ID: ApricityUI can change storage without
            // changing the ID. Query immediately before each portal depth copy.
            GlStateManager._bindTexture(sourceTexture);
            int sourceFormat = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            GlStateManager._bindTexture(destinationTexture);
            int destinationFormat = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);

            boolean adjusted = PortalDepthStencilFormat.matchPackedSource(
                sourceFormat, destinationFormat, format -> {
                    GlStateManager._texImage2D(
                        GL_TEXTURE_2D, 0, format, destination.width, destination.height, 0,
                        GL_DEPTH_STENCIL,
                        format == GL_DEPTH32F_STENCIL8 ? GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL_UNSIGNED_INT_24_8,
                        (IntBuffer) null
                    );
                    GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
                    GlStateManager._glFramebufferTexture2D(
                        GL_DRAW_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D,
                        destinationTexture, 0
                    );
                    destination.checkStatus();
                }
            );
            if (adjusted && reportedAdjustments < 8) {
                reportedAdjustments++;
                Helper.log("[IP depth compatibility] Matched portal scratch depth: source="
                    + sourceTexture + " destination=" + destinationTexture + " format=0x"
                    + Integer.toHexString(sourceFormat) + " previous=0x"
                    + Integer.toHexString(destinationFormat));
            }
        }
        finally {
            GlStateManager._bindTexture(oldTexture);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldDrawFramebuffer);
        }
    }
}
