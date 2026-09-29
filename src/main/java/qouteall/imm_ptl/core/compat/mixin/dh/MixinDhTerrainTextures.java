package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge", remap = false)
public class MixinDhTerrainTextures {
    @Unique private int ip_continuousGradients = -2;

    @Inject(method = "fillUniformData", at = @At("RETURN"))
    private void ip_uploadTexturePolicy(DhApiRenderParam params, CallbackInfo ci) {
        var program = (GlShaderProgram)(Object)this;
        if (ip_continuousGradients == -2) {
            ip_continuousGradients = program.tryGetUniformLocation("uIpContinuousTextureGradients");
        }
        if (ip_continuousGradients >= 0) {
            program.setUniform(ip_continuousGradients,
                DhPortalRendering.isSupportedPass() && !IrisInterface.invoker.isShaders());
        }
    }
}
