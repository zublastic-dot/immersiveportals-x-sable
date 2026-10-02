package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;

@Mixin(value = JcppProcessor.class, remap = false)
public class MixinIrisPortalShaderPreprocessor {
    @Inject(method = "glslPreprocessSource", at = @At("RETURN"))
    private static void ip_observeSourceOptions(String source, Iterable<StringPair> defines,
                                                CallbackInfoReturnable<String> cir) {
        if (Iris.getIrisConfig() == null) return;
        // This family has a separate END_SUN_ANGLE. Never publish that setting as
        // the source Overworld's direction when an End pipeline is compiled later.
        String header = source.substring(0, Math.min(512, source.length()));
        if (!header.contains("#define OVERWORLD") && !header.contains("#define NETHER")) return;
        PortalShaderPackAdapter.observePreprocessed(
            Iris.getIrisConfig().getShaderPackName().orElse(""), cir.getReturnValue());
    }
}
