package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalPipelineScope;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.function.Consumer;

@Mixin(value = MyGameRenderer.class, remap = false)
public class MixinIrisWorldRender {
    @WrapMethod(method = "renderWorldNew")
    private static void ip_restoreParentIrisPipeline(WorldRenderInfo info, Consumer<Runnable> wrapper,
                                                    Operation<Void> original) {
        // The native method's finally restores Minecraft.level, camera and render
        // stack before this closes, including failed and recursively nested passes.
        try (var scope = IrisPortalPipelineScope.begin()) { original.call(info, wrapper); }
    }
}
