package qouteall.imm_ptl.core.compat.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.*;

@Pseudo
@Mixin(targets = {"com.seibel.distanthorizons.core.level.DhClientLevel",
    "com.seibel.distanthorizons.core.level.DhClientServerLevel"}, remap = false)
public class MixinDhClientLevel implements DhPortalLevel {
    @Unique private volatile DhPortalView ip_dhView;
    @Unique private boolean ip_dhBuffersReported;

    @Override public DhPortalView ip_getDhView() { return ip_dhView; }
    @Override public void ip_setDhView(DhPortalView view) { ip_dhView = view; }
    @Override public boolean ip_markDhBuffersReported() {
        if (ip_dhBuffersReported) return false;
        ip_dhBuffersReported = true;
        return true;
    }

    @WrapMethod(method = "clientTick")
    private void ip_tickVisibleDestination(Operation<Void> original) {
        DhPortalView view = ip_dhView;
        var actual = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class).getWrappedClientLevel();
        // DH has one quadtree per level. The actual player's dimension takes priority.
        if (view == null || !view.isRecent() || view.level().getDhLevel() != (Object)this
            || (actual != null && actual.getDhLevel() == (Object)this)) {
            original.call();
            return;
        }
        try (var scope = DhPortalRendering.TICK_VIEW.push(view)) { original.call(); }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void ip_releaseView(CallbackInfo ci) { ip_dhView = null; }
}
