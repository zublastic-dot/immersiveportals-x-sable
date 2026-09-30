package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.lwjgl.opengl.GL33;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa;
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
            int donatedPhase = DhPortalTaa.mainPhaseBeforeIncrement();
            if (donatedPhase >= 0) frameIndexMod8 = donatedPhase;
            original.call(params);
            DhPortalTaa.recordMainSample(frameIndexMod8);
            return;
        }

        int outerFrameIndex = frameIndexMod8;
        try {
            original.call(params);
            // One phase per complete portal path and outer frame, including repeated uploads.
            // Respect DH's AA/shader veto, and never consume the main view's counter.
            GL33.glUniform1f(uFrameMod8, frameIndexMod8 < 0 ? -1.0f : DhPortalTaa.jitterPhase());
        } finally {
            // Portal uniform uploads must not consume the normal view's phase.
            frameIndexMod8 = outerFrameIndex;
        }
    }
}
