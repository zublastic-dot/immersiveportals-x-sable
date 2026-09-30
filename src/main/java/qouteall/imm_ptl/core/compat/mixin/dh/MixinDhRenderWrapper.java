package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_neoforge", remap = false)
public class MixinDhRenderWrapper {
    @Inject(method = "getLookAtVector", at = @At("HEAD"), cancellable = true)
    private void ip_destinationLookDirection(CallbackInfoReturnable<DhVec3f> cir) {
        var direction = DhPortalRendering.getPortalLookDirection();
        if (direction != null) cir.setReturnValue(direction);
    }

    @Inject(method = "getCameraExactPosition", at = @At("HEAD"), cancellable = true)
    private void ip_tickCamera(CallbackInfoReturnable<DhVec3d> cir) {
        var view = DhPortalRendering.TICK_VIEW.current();
        if (view != null) cir.setReturnValue(new DhVec3d(view.x(), view.y(), view.z()));
    }

    @Inject(method = "getRenderDistance", at = @At("HEAD"), cancellable = true)
    private void ip_viewRenderDistance(CallbackInfoReturnable<Integer> cir) {
        if (WorldRenderInfo.isRendering()) cir.setReturnValue(
            DhPortalRendering.vanillaCoverageDistance(WorldRenderInfo.getRenderDistance()));
    }

    @Inject(method = "getLightmapClientLevelWrapper", at = @At("HEAD"), cancellable = true)
    private static void ip_lightmapOwner(CallbackInfoReturnable<IClientLevelWrapper> cir) {
        Minecraft mc = Minecraft.getInstance();
        // IP updates the destination LightTexture before DH's renderSectionLayer hook.
        // RENDER_STATE at that point still describes the previous (usually outer) view.
        if (mc.isSameThread() && mc.level != null) {
            cir.setReturnValue(ClientLevelWrapper_neoforge.getWrapper(mc.level, false));
        }
    }
}
