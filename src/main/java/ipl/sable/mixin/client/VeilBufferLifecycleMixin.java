package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.GlStateManager;
import ipl.sable.render.GlObjectLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * glGen* reserves names only. Veil's debug labels and VertexArray DSA uploads
 * require live objects before vanilla's first bind. Instantiate at allocation,
 * restoring bindings immediately; never swallow GL errors or arbitrary bad IDs.
 */
@Mixin(GlStateManager.class)
public abstract class VeilBufferLifecycleMixin {
    @ModifyReturnValue(method = "_glGenBuffers", at = @At("RETURN"), require = 1)
    private static int ipl$initializeBuffer(int name) {
        return GlObjectLifecycle.initializeGeneratedBuffer(name);
    }

    @ModifyReturnValue(method = "_glGenVertexArrays", at = @At("RETURN"), require = 1)
    private static int ipl$initializeVertexArray(int name) {
        return GlObjectLifecycle.initializeGeneratedVertexArray(name);
    }
}
