package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.lwjgl.opengl.GL33;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge", remap = false)
public class MixinDhTerrainAntiAliasing {
    @Shadow private int frameIndexMod8;
    @Shadow public int uFrameMod8;

    @WrapMethod(method = "fillUniformData")
    private void ip_stablePortalSamples(DhApiRenderParam params, Operation<Void> original) {
        if (!PortalRendering.isRendering()) {
            original.call(params);
            return;
        }

        int outerFrameIndex = frameIndexMod8;
        try {
            original.call(params);
            // MixinDhAntiAliasing skips shared temporal history for portal views.
            // DH still uploads an animated sample offset even with a stationary
            // camera. Its bound terrain shader uses -1 for unjittered sampling.
            GL33.glUniform1f(uFrameMod8, -1.0f);
        } finally {
            // Portal uniform uploads must not consume the normal view's phase.
            frameIndexMod8 = outerFrameIndex;
        }
    }
}
