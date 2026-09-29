package qouteall.imm_ptl.core.compat.mixin.dh;

import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalSsao;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTextures;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader", remap = false)
public class MixinDhShaderSource {
    @Inject(method = "loadFile", at = @At("RETURN"), cancellable = true)
    private static void ip_portalShaderCompatibility(String path, boolean absoluteFilePath,
                                           CallbackInfoReturnable<String> cir) {
        if (absoluteFilePath) return;
        String source = cir.getReturnValue();
        String patched;
        if (DhPortalSsao.APPLY_SHADER.equals(path)) patched = DhPortalSsao.patchApplyShader(source);
        else if (DhPortalTextures.TERRAIN_SHADER.equals(path)) patched = DhPortalTextures.patchTerrainShader(source);
        else return;
        if (patched.equals(source)) {
            LogUtils.getLogger().warn("IP/Sable DH: unrecognized shader {}; portal adaptation unavailable", path);
        }
        cir.setReturnValue(patched);
    }
}
