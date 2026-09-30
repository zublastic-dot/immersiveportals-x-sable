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
import qouteall.imm_ptl.core.portal.Portal;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaShader_neoforge;

/** Optional-DH boundary; only the ordinary-depth, no-shader portal path uses private TAA. */
public final class DhPortalTaa {
    private static final DhPortalTaaPipeline PIPELINE = new DhPortalTaaPipeline();
    private static boolean registered;
    private static boolean reportedAccumulation;
    private static final DhTaaCrossing CROSSING = new DhTaaCrossing();
    private static DhPortalTaaPipeline.Transfer transfer;
    private static boolean seedMain;
    private static int mainFrame = -1, mainPhase = -1;
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
    public static void clear() {
        PIPELINE.close(); reportedAccumulation = false; CROSSING.clear(); transfer = null;
        seedMain = false; mainFrame = mainPhase = -1;
    }

    public static void crossed(Portal portal) {
        if (!enabled()) return;
        CROSSING.crossed(portal.getOriginDim(), portal.getDestDim(), portal.getUUID(), RenderStates.frameIndex, System.nanoTime());
    }

    /** Select before terrain uniforms so the first main sample follows the donated portal sample. */
    public static void prepareMain(RenderParams params) {
        if (PortalRendering.isRendering() || !enabled() || !CROSSING.pending()) return;
        mainFrame = RenderStates.frameIndex; mainPhase = -1; transfer = null; seedMain = true;
        var key = CROSSING.take(RenderStates.originalPlayerDimension, mainFrame, System.nanoTime());
        if (key == null || params.exactCameraPosition == null) return;
        var render = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
        var api = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
        transfer = PIPELINE.transfer(key, mainFrame, System.nanoTime(),
            render.getTargetFramebufferViewportWidth(), render.getTargetFramebufferViewportHeight(),
            matrix(params.dhProjectionMatrix), matrix(params.dhModelViewMatrix), camera(params),
            api.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);
        if (transfer != null) mainPhase = transfer.snapshot().phase();
    }

    public static int mainPhaseBeforeIncrement() {
        return enabled() && mainFrame == RenderStates.frameIndex ? mainPhase : -1;
    }

    /** Called inside DH's render, after its allocation/resize and before it reads main history. */
    public static void seedMainHistory(int framebuffer, int width, int height) {
        if (!seedMain || PortalRendering.isRendering() || !enabled()) return;
        seedMain = false;
        boolean copied = DhPortalTaaPipeline.seed(mainFrame == RenderStates.frameIndex ? transfer : null, framebuffer, width, height);
        if (copied) ((DhTaaPreviousFrame)(Object)GlDhTaaShader_neoforge.INSTANCE).ip_acceptPortalHistory(transfer.snapshot());
        com.mojang.logging.LogUtils.getLogger().info("IP/Sable DH: portal crossing TAA history {}", copied ? "transferred" : "reset (no compatible recent portal view)");
        transfer = null;
    }

    private static DhPortalTaaPipeline.View view(RenderParams params) {
        if (params == null || !enabled() || !DhPortalRendering.isSupportedPass()
            || DhPortalRendering.getGeometryClipPlane() == null || params.exactCameraPosition == null) return null;
        var render = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
        var api = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
        var path = PortalRendering.getPortalPath();
        var key = new DhTaaCrossing.Key(RenderStates.originalPlayerDimension, path.getLast().getDestDim(),
            path.stream().map(p -> p.getUUID()).toList());
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
