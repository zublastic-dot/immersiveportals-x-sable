package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge;
import com.seibel.distanthorizons.core.render.RenderParams;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/** Loaded only by the version-admitted DH mixins. All GL reads require a finite trace budget. */
public final class DhRenderTraceProbe {
    private DhRenderTraceProbe() {}

    public static void sample(RenderParams params, String phase) {
        if (!DhRenderTrace.active()) return;
        var mc = Minecraft.getInstance();
        String dimension = mc.level == null ? "none" : mc.level.dimension().location().toString();
        boolean shadow = IrisInterface.invoker.isRenderingShadowMap();
        String key = dimension + "/" + PortalRendering.getPortalLayer() + "/" + phase + "/"
            + params.renderPass + "/" + shadow + "/" + PortalSourceRefreshPolicy.isRendering();
        if (!DhRenderTrace.reserve(key)) return;
        try {
            var nativeRenderer = GlDhMetaRenderer_neoforge.INSTANCE;
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int depthType = draw == 0 ? 0 : GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int depth = depthType == GL11.GL_NONE ? 0 : GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            var buffers = params.renderBufferHandler == null ? null : params.renderBufferHandler.getColumnRenderBuffers();
            String iris = IrisInterface.invoker.isIrisPresent() ? IrisData.describe() : "iris=absent";
            String projection = "selectedBuffer".equals(phase) && IrisInterface.invoker.isShaders()
                ? DhIrisProjectionDiagnostics.capture(program, params) : "";
            DhRenderTrace.record(key, key + " timeNs=" + System.nanoTime()
                + " wrapper=" + (params.clientLevelWrapper == null ? "null" : params.clientLevelWrapper.getDimensionName())
                + " wrapperId=" + id(params.clientLevelWrapper) + " dhLevel=" + id(params.dhClientLevel)
                + " buffers=" + id(params.renderBufferHandler) + ":" + (buffers == null ? -1 : buffers.size())
                + " camera=" + params.exactCameraPosition + " glProgram=" + program
                + " drawFbo=" + draw + " readFbo=" + read + " depthAttachment=" + depthType + ":" + depth
                + " dhFbo=" + nativeRenderer.getActiveFramebufferId()
                + " dhDepth=" + nativeRenderer.getActiveDepthTextureId() + " dhColor=" + nativeRenderer.getActiveColorTextureId()
                + " depthClear=" + GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE)
                + " depthWrite=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                + " scissor=" + GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
                + " stencil=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                + " " + iris + " " + projection);
        } catch (RuntimeException | LinkageError failure) {
            // Diagnostics must never break a native render pass or retry without consuming budget.
            DhRenderTrace.record(key, key + " traceFailure=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    private static String id(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static final class IrisData {
        static String describe() {
            var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            var dh = pipeline == null ? null : pipeline.getDHCompat();
            return "irisDimension=" + net.irisshaders.iris.Iris.getCurrentDimension()
                + " irisPipeline=" + id(pipeline) + " irisDh=" + id(dh)
                + " irisDepth=" + (dh == null ? -1 : dh.getDepthTex())
                + " irisDepthOpaque=" + (dh == null ? -1 : dh.getDepthTexNoTranslucent());
        }
    }
}
