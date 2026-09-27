package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.render.CameraZoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Pseudo
@Mixin(value = ClientApi.class, remap = false)
public class MixinDhClientApi {
    @WrapMethod(method = "renderLodLayer")
    private void ip_renderScope(boolean deferred, Operation<Void> original) {
        if (!PortalRendering.isRendering()) { original.call(deferred); return; }
        try (var scope = DhPortalRendering.begin()) {
            original.call(deferred);
        }
    }

    @WrapOperation(method = "renderLodLayer", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/render/CameraZoom;update(Lcom/seibel/distanthorizons/core/api/internal/rendering/DhRenderState;)V"))
    private void ip_keepMainZoom(CameraZoom zoom, DhRenderState state, Operation<Void> original) {
        if (!PortalRendering.isRendering()) original.call(zoom, state);
    }
}
