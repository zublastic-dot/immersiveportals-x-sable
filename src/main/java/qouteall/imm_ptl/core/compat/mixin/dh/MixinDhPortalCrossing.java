package qouteall.imm_ptl.core.compat.mixin.dh;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhPortalTaa;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;

@Mixin(value = ClientTeleportationManager.class, remap = false)
public class MixinDhPortalCrossing {
    // This write occurs only after a successful teleport; RETURN would also admit rejected teleports.
    @Inject(method = "teleportPlayer", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
        target = "Lqouteall/imm_ptl/core/teleportation/ClientTeleportationManager;isTeleportingFrame:Z"))
    private static void ip_crossed(TeleportationUtil.Teleportation teleportation, float partialTicks, CallbackInfo ci) {
        DhPortalTaa.crossed(teleportation.portal());
    }
}
