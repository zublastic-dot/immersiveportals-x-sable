package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

/** DH references are isolated from the optional-dependency/version check. */
public final class DhPortalRendering {
    public static final DhScopedContext<DhPortalView> TICK_VIEW = new DhScopedContext<>();
    private static final DhScopedContext<Pass> PASS = new DhScopedContext<>();

    private DhPortalRendering() {}

    public static boolean isSupportedPass() {
        Pass pass = PASS.current();
        return pass != null && pass.valid;
    }

    public static Pass begin() { return new Pass(); }

    public static DhVec3f getPortalLookDirection() {
        Pass pass = PASS.current();
        if (pass == null || !pass.valid || pass.lookDirection == null) return null;
        // DH normalizes the returned vector while culling cloud groups.
        Vector3f direction = pass.lookDirection;
        return new DhVec3f(direction.x, direction.y, direction.z);
    }

    public static DhPortalTextureSnapshots.Scope preserveOuterImages() {
        // Iris owns its shader/deferred targets. This fixes DH's no-shader fade path.
        if (IrisInterface.invoker.isShaders()) return null;
        var renderer = GlDhMetaRenderer_neoforge.INSTANCE;
        return DhPortalTextureSnapshots.capture(renderer.getActiveColorTextureId(), renderer.getActiveDepthTextureId());
    }

    public static void prepare(RenderParams params) {
        if (!PortalRendering.isRendering() || PASS.current() == null) return;
        // IP moves the Camera position but applies portal rotation/reflection to
        // the model-view matrix. Camera.getLookVector() stays in the outer world.
        PASS.current().lookDirection = DhPortalCamera.lookDirection(toJoml(params.dhModelViewMatrix));
        if (params.dhClientLevel instanceof DhPortalLevel level && params.exactCameraPosition != null) {
            var camera = params.exactCameraPosition;
            if (level.ip_getDhView() == null) {
                LogUtils.getLogger().info("IP/Sable DH: preparing portal view of {} at {}, {}, {}",
                    params.clientLevelWrapper.getDimensionName(), camera.x, camera.y, camera.z);
            }
            level.ip_setDhView(new DhPortalView(params.clientLevelWrapper,
                camera.x, camera.y, camera.z, System.nanoTime()));
        }

        var plane = PortalRendering.getActiveClippingPlane();
        if (plane == null || params.exactCameraPosition == null) return;
        var n = plane.normal();
        var camera = params.exactCameraPosition;
        double d = n.x * (camera.x - plane.pos().x)
            + n.y * (camera.y - plane.pos().y) + n.z * (camera.z - plane.pos().z);
        var renderApi = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
        Matrix4f clipped = DhPortalProjection.clip(toJoml(params.dhProjectionMatrix),
            toJoml(params.dhModelViewMatrix), new Vector4f((float)n.x, (float)n.y, (float)n.z, (float)d),
            renderApi.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z);
        if (clipped == null) {
            PASS.current().valid = false;
            return;
        }
        DhMat4f result = new DhMat4f();
        result.set(clipped);
        DhPortalMatrices.applyProjection(params, result);
        params.apiCopy.update(params);
    }

    private static Matrix4f toJoml(DhApiMat4f matrix) {
        float[] values = new float[16];
        matrix.putValuesInArray(values);
        return new Matrix4f().setTransposed(values);
    }

    public static void report(RenderParams params) {
        if (!isSupportedPass() || params == null || params.renderBufferHandler == null) return;
        var buffers = params.renderBufferHandler.getColumnRenderBuffers();
        if (buffers != null && buffers.size() > 0
            && params.dhClientLevel instanceof DhPortalLevel level && level.ip_markDhBuffersReported()) {
            LogUtils.getLogger().info("IP/Sable DH: portal LOD render submitted for {} ({} buffers)",
                params.clientLevelWrapper.getDimensionName(), buffers.size());
        }
    }

    public static final class Pass implements AutoCloseable {
        private final GLState gl = new GLState();
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final boolean clipDistance = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        private final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        private final float[] clearColor = new float[4];
        private final DhScopedContext.Scope scope;
        private boolean valid = true;
        private Vector3f lookDirection;

        private Pass() {
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            scope = PASS.push(this);
            // DH and its fullscreen post shaders do not write gl_ClipDistance.
            // Clip its terrain using the projection instead; preserve IP's stencil mask.
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        }

        @Override public void close() {
            try {
                gl.close();
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GL11.glClearDepth(clearDepth);
                GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
                if (clipDistance) GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
                else GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            } finally { scope.close(); }
        }
    }
}
