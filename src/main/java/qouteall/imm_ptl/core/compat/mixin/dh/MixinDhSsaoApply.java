package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.ssao.GlDhSSAOApplyShader_neoforge", remap = false)
public abstract class MixinDhSsaoApply extends GlAbstractShaderRenderer {
    @Unique private int ip_portalProjection = -1;
    @Unique private int ip_depthZeroToOne = -1;
    @Unique private int ip_inverseProjection = -1;

    @Inject(method = "onInit", at = @At("RETURN"))
    private void ip_findDepthUniforms(CallbackInfo ci) {
        ip_portalProjection = shader.tryGetUniformLocation("uIpPortalProjection");
        ip_depthZeroToOne = shader.tryGetUniformLocation("uIpDepthZeroToOne");
        ip_inverseProjection = shader.tryGetUniformLocation("uIpInverseProjection");
    }

    @Inject(method = "onApplyUniforms", at = @At("RETURN"))
    private void ip_useThisViewDepth(RenderParams params, CallbackInfo ci) {
        if (ip_portalProjection < 0 || ip_depthZeroToOne < 0 || ip_inverseProjection < 0) return;
        boolean portal = DhPortalRendering.usesObliqueProjection() && !IrisInterface.invoker.isShaders();
        // Set on every draw: an outer/sibling view must not inherit the portal flag.
        shader.setUniform(ip_portalProjection, portal);
        if (portal) {
            var inverse = new DhMat4f(params.dhProjectionMatrix);
            inverse.invert();
            shader.setUniform(ip_inverseProjection, inverse);
            shader.setUniform(ip_depthZeroToOne, RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);
        }
    }
}
