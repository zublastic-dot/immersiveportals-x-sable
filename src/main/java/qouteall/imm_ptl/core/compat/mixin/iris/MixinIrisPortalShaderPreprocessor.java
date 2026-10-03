package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;
import qouteall.imm_ptl.core.sunlight.SunlightClient;

@Mixin(value = JcppProcessor.class, remap = false)
public class MixinIrisPortalShaderPreprocessor {
    @WrapMethod(method = "glslPreprocessSource", require = 1)
    private static String ip_observeSourceOptions(String source, Iterable<StringPair> defines,
                                                  Operation<String> original) {
        // Iris reassigns its source argument to the preprocessed text before
        // returning. A RETURN injector therefore loses the dimension directives.
        // A wrapper keeps the original immutable source in a separate call frame.
        String preprocessed = original.call(source, defines);
        if (Iris.getIrisConfig() == null) return preprocessed;
        // This family has a separate END_SUN_ANGLE. Never publish that setting as
        // the source Overworld's direction when an End pipeline is compiled later.
        String header = source.substring(0, Math.min(512, source.length()));
        if (!header.contains("#define OVERWORLD") && !header.contains("#define NETHER")) return preprocessed;
        String pack = Iris.getIrisConfig().getShaderPackName().orElse("");
        // Observe local settings before adapting; never publish an inherited
        // server profile as if it were the owner's option selection.
        SunlightClient.observeOwner(pack, preprocessed);
        String adapted = SunlightClient.adapt(pack, source);
        if (adapted != source) preprocessed = original.call(adapted, defines);
        PortalShaderPackAdapter.observePreprocessed(pack, preprocessed);
        return preprocessed;
    }
}
