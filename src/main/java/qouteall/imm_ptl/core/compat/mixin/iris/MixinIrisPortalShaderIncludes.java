package qouteall.imm_ptl.core.compat.mixin.iris;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalShaderPackAdapter;

/** Iris has expanded includes and applied options here, but has not run JCPP yet. */
@Mixin(value = IncludeProcessor.class, remap = false)
public class MixinIrisPortalShaderIncludes {
    @Inject(method = "getIncludedFile", at = @At("RETURN"), cancellable = true)
    private void ip_adaptPortalSun(AbsolutePackPath path,
                                  CallbackInfoReturnable<ImmutableList<String>> cir) {
        var original = cir.getReturnValue();
        if (original == null || Iris.getIrisConfig() == null) return;
        String selected = Iris.getIrisConfig().getShaderPackName().orElse("");
        var patched = PortalShaderPackAdapter.patch(selected, path.getPathString(), original);
        patched = DhPortalShaderPackAdapter.patch(selected, path.getPathString(), patched);
        // The IncludeProcessor cache is immutable and must remain the original pack.
        if (patched != original) cir.setReturnValue(ImmutableList.copyOf(patched));
    }
}
