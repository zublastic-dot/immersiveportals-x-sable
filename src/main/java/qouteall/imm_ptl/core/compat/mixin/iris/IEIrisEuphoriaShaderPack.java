package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Iris.class, remap = false)
public interface IEIrisEuphoriaShaderPack {
    @Accessor("currentPack")
    static void ip_setCurrentPack(ShaderPack pack) {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
