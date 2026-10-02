package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhCameraSpeedHistory;
import java.util.function.UnaryOperator;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.lighting.PortalSourceRefreshPolicy;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Pseudo
@Mixin(value = ClientApi.class, remap = false)
public class MixinDhClientApi implements DhCameraSpeedHistory {
    @Shadow private DhVec3d lastCameraPosForSpeedCheck;
    @Shadow private long msSinceLastSpeedCheck;

    @Override public boolean ip_rebaseCameraSpeed(UnaryOperator<DhVec3d> transform) {
        if (msSinceLastSpeedCheck == 0 || lastCameraPosForSpeedCheck == null) return false;
        // Copy: a mutable callback must not damage the old sample on failure.
        DhVec3d rebased = transform.apply(new DhVec3d(lastCameraPosForSpeedCheck));
        if (rebased == null || !Double.isFinite(rebased.x) || !Double.isFinite(rebased.y) || !Double.isFinite(rebased.z)) return false;
        lastCameraPosForSpeedCheck = rebased;
        // Keep the sample time and rolling average: real walking/flying still counts.
        return true;
    }
    @WrapMethod(method = "renderLodLayer")
    private void ip_renderScope(boolean deferred, Operation<Void> original) {
        // The discarded source color image must not update the main view's DH
        // history. Native source shadow geometry is still needed by Iris.
        if (PortalSourceRefreshPolicy.isRendering()) {
            if (IrisInterface.invoker.isRenderingShadowMap()) original.call(deferred);
            return;
        }
        DhPortalTaa.maintain();
        if (!PortalRendering.isRendering()) { original.call(deferred); return; }
        try (var scope = DhPortalRendering.begin()) {
            original.call(deferred);
        }
    }

    @WrapMethod(method = {"renderFadeOpaque", "renderFadeTransparent"})
    private void ip_fadeScope(Operation<Void> original) {
        if (PortalSourceRefreshPolicy.isRendering() && !IrisInterface.invoker.isRenderingShadowMap()) return;
        if (!PortalRendering.isRendering()) { original.call(); return; }
        // Fade rebuilds RenderParams after renderLodLayer has returned. Reapply
        // the same oblique projection and disable clip distance for its quad.
        try (var scope = DhPortalRendering.begin()) {
            original.call();
        }
    }

    @WrapOperation(method = "shouldRenderFade", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor;isRenderingPortal()Z"))
    private static boolean ip_allowScopedFade(IImmersivePortalsAccessor accessor, Operation<Boolean> original) {
        // Preserve DH's shader-pack veto and every non-portal decision.
        return original.call(accessor) && !DhPortalRendering.isSupportedPass();
    }

    @WrapOperation(method = {"renderFadeOpaque", "renderFadeTransparent"}, at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhVanillaFadeRenderer;render(Lcom/seibel/distanthorizons/core/render/RenderParams;)V"), require = 2)
    private void ip_fadeOnlyValidView(IDhVanillaFadeRenderer renderer, RenderParams params, Operation<Void> original) {
        // A degenerate clip plane suppresses terrain too; do not fade toward an
        // image that could not be rendered for this view.
        if (!PortalRendering.isRendering() || DhPortalRendering.isSupportedPass()) original.call(renderer, params);
    }

    @WrapOperation(method = "renderLodLayer", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/render/CameraZoom;update(Lcom/seibel/distanthorizons/core/api/internal/rendering/DhRenderState;)V"))
    private void ip_keepMainZoom(CameraZoom zoom, DhRenderState state, Operation<Void> original) {
        if (!PortalRendering.isRendering() && !PortalSourceRefreshPolicy.isRendering()) original.call(zoom, state);
    }

    @WrapOperation(method = "renderLodLayer", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor;isRenderingPortal()Z"), require = 1)
    private boolean ip_keepMainCameraSpeed(IImmersivePortalsAccessor accessor, Operation<Boolean> original) {
        // DH samples camera speed before checking whether this is a shadow pass.
        // This one call excludes auxiliary views from that sample only; it does
        // not pretend to be a portal at the independent DH render-event veto.
        return PortalSourceRefreshPolicy.isRendering() || original.call(accessor);
    }
}
