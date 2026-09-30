package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import java.util.List;
import java.util.UUID;

/** Optional-DH boundary; only the ordinary-depth, no-shader portal path uses private TAA. */
public final class DhPortalTaa {
    private static final DhPortalTaaPipeline PIPELINE = new DhPortalTaaPipeline();
    private static boolean registered;
    private static boolean reportedAccumulation;
    private record Key(Object origin, Object destination, List<UUID> path) {}
    private DhPortalTaa() {}

    private static boolean enabled() {
        return !IrisInterface.invoker.isShaders() && Config.Client.Advanced.Graphics.enableAntiAliasing.get();
    }
    public static void maintain() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, e -> clear());
        }
        if (!enabled()) clear();
        else PIPELINE.prune(RenderStates.frameIndex, System.nanoTime());
    }
    public static void clear() { PIPELINE.close(); reportedAccumulation = false; }

    private static DhPortalTaaPipeline.View view(RenderParams params) {
        if (params == null || !enabled() || !DhPortalRendering.isSupportedPass()
            || DhPortalRendering.getGeometryClipPlane() == null || params.exactCameraPosition == null) return null;
        var render = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
        var api = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
        var key = new Key(RenderStates.originalPlayerDimension, params.clientLevelWrapper,
            PortalRendering.getPortalPath().stream().map(p -> p.getUUID()).toList());
        return PIPELINE.prepare(key, RenderStates.frameIndex, System.nanoTime(),
            render.getTargetFramebufferViewportWidth(), render.getTargetFramebufferViewportHeight(),
            matrix(params.dhProjectionMatrix), matrix(params.dhModelViewMatrix), camera(params),
            api.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);
    }
    public static int jitterPhase() {
        var view = view(DhPortalRendering.currentParams());
        return view == null ? -1 : view.history.phase();
    }
    public static void render(RenderParams params) {
        var view = view(params);
        if (view == null) return;
        var renderer = GlDhMetaRenderer_neoforge.INSTANCE;
        PIPELINE.render(view, renderer.getActiveColorTextureId(), renderer.getActiveDepthTextureId(),
            renderer.getActiveFramebufferId(), matrix(params.dhProjectionMatrix), matrix(params.dhModelViewMatrix), camera(params));
        if (view.history.valid && !reportedAccumulation) {
            reportedAccumulation = true;
            com.mojang.logging.LogUtils.getLogger().info(
                "IP/Sable DH: isolated portal TAA history accumulating for {} (portal depth {})",
                params.clientLevelWrapper.getDimensionName(), PortalRendering.getPortalLayer());
        }
    }
    private static Vector3d camera(RenderParams params) {
        var p = params.exactCameraPosition; return new Vector3d(p.x, p.y, p.z);
    }
    private static Matrix4f matrix(DhApiMat4f matrix) {
        float[] values = new float[16]; matrix.putValuesInArray(values);
        return new Matrix4f().setTransposed(values);
    }
}
