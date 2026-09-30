package qouteall.imm_ptl.core.compat.mixin.dh;

import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalClipping;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram", remap = false)
public abstract class MixinDhGeometryClipping {
    @Shadow public abstract int tryGetUniformLocation(CharSequence name);
    @Unique private int ip_clipPlaneLocation = -2;
    @Unique private qouteall.imm_ptl.core.lighting.PortalLightGpu.Binding ip_lightBinding;

    @Inject(method="unbind()V",at=@At("HEAD"))
    private void ip_restoreLight(CallbackInfo ci) {
        if(ip_lightBinding!=null) { ip_lightBinding.close(); ip_lightBinding=null; }
    }

    @Inject(method = "bind()V", at = @At("RETURN"))
    private void ip_setThisViewPlane(CallbackInfo ci) {
        if(ip_lightBinding!=null) ip_lightBinding.close();
        ip_lightBinding=qouteall.imm_ptl.core.lighting.PortalLightGpu.bind();
        if (ip_clipPlaneLocation == -2) ip_clipPlaneLocation = tryGetUniformLocation(DhPortalClipping.UNIFORM);
        if (ip_clipPlaneLocation < 0) return;
        var plane = DhPortalRendering.getGeometryClipPlane();
        // Zero explicitly disables clipping in direct, shader-pack and sibling views.
        GL20.glUniform4f(ip_clipPlaneLocation, plane == null ? 0 : plane.x,
            plane == null ? 0 : plane.y, plane == null ? 0 : plane.z, plane == null ? 0 : plane.w);
    }
}
