package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.GlObjectLifecycle;
import org.spongepowered.asm.mixin.Mixin;

/**
 * glGen* reserves names only. Veil's debug labels and VertexArray DSA uploads
 * require live objects before vanilla's first bind. Instantiate at allocation,
 * restoring bindings immediately; never swallow GL errors or arbitrary bad IDs.
 */
@Mixin(GlStateManager.class)
public abstract class VeilBufferLifecycleMixin {
    // IP's own cache returns through a cancellable HEAD injection. RETURN
    // injectors miss that generated early return, so wrap the entire operation.
    @WrapMethod(method = "_glGenBuffers", require = 1)
    private static int ipl$initializeBuffer(Operation<Integer> original) {
        return GlObjectLifecycle.initializeGeneratedBuffer(original.call());
    }

    @WrapMethod(method = "_glGenVertexArrays", require = 1)
    private static int ipl$initializeVertexArray(Operation<Integer> original) {
        return GlObjectLifecycle.initializeGeneratedVertexArray(original.call());
    }
}
