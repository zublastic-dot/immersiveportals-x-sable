package qouteall.imm_ptl.core.compat.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisFramebufferDepthOwnership;

/**
 * Iris owns DH framebuffer lifetime and colour targets, but DH owns their depth.
 * A main-target replacement must not overwrite that depth with vanilla depth:
 * DH's reconnect cache still sees its unchanged texture and cannot repair it.
 */
@Mixin(value = RenderTargets.class, remap = false)
public class MixinIrisDhDepthOwnership {
    @Unique private final IrisFramebufferDepthOwnership<GlFramebuffer> ip_dhDepthOwnership =
        new IrisFramebufferDepthOwnership<>();

    // RETURN also covers the empty draw-buffer path through createEmptyFramebuffer.
    @Inject(method = "createDHFramebuffer", at = @At("RETURN"))
    private void ip_markDhDepth(ImmutableSet<Integer> flipped, int[] drawBuffers,
                                CallbackInfoReturnable<GlFramebuffer> cir) {
        ip_dhDepthOwnership.external(cir.getReturnValue());
    }

    @WrapOperation(method = "resizeIfNeeded", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;addDepthAttachment(I)V"))
    private void ip_preserveDhDepth(GlFramebuffer framebuffer, int texture, Operation<Void> original) {
        ip_dhDepthOwnership.updateMainDepth(framebuffer, texture, original::call);
    }

    @Inject(method = "destroyFramebuffer", at = @At("RETURN"))
    private void ip_forgetDestroyedDhFramebuffer(GlFramebuffer framebuffer, CallbackInfo ci) {
        ip_dhDepthOwnership.remove(framebuffer);
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void ip_forgetDhFramebuffers(CallbackInfo ci) {
        ip_dhDepthOwnership.clear();
    }
}
