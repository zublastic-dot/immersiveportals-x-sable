package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import java.util.function.Consumer;

@Mixin(value = MyGameRenderer.class, remap = false)
public class MixinDhWorldRender {
    @WrapMethod(method = "renderWorldNew")
    private static void ip_restoreOuterDhState(WorldRenderInfo info, Consumer<Runnable> wrapper,
                                               Operation<Void> original) {
        var state = ClientApi.RENDER_STATE;
        var modelView = new DhMat4f(state.mcModelViewMatrix);
        var projection = new DhMat4f(state.mcProjectionMatrix);
        var level = state.clientLevelWrapper;
        float partial = state.partialTickTime;
        boolean fog = state.vanillaFogEnabled;
        try { original.call(info, wrapper); }
        finally {
            state.mcModelViewMatrix = modelView;
            state.mcProjectionMatrix = projection;
            state.clientLevelWrapper = level;
            state.partialTickTime = partial;
            state.vanillaFogEnabled = fog;
        }
    }
}
