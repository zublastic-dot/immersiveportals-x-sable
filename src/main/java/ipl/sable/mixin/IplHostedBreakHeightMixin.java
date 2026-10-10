package ipl.sable.mixin;

import ipl.sable.dim.IplHostedPositionHeight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

/** Normalize the height argument shared by vanilla and IP cross-portal block-break packets. */
@Mixin(ServerPlayerGameMode.class)
public abstract class IplHostedBreakHeightMixin {
    @Shadow protected ServerLevel level;

    @ModifyVariable(
        method = "handleBlockBreakAction(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
            + "Lnet/minecraft/core/Direction;II)V",
        at = @At("HEAD"), argsOnly = true, index = 4, require = 1
    )
    private int ipl$breakTargetStorageMaximum(
        int original, BlockPos pos, ServerboundPlayerActionPacket.Action action,
        Direction direction, int maximum, int sequence
    ) {
        // Match IP's existing game-mode world redirection, including cross-portal callers.
        var redirect = BlockManipulationServer.REDIRECT_CONTEXT.get();
        ServerLevel targetWorld = redirect == null ? level : redirect.world();
        return IplHostedPositionHeight.maximumForPosition(targetWorld, pos, original);
    }
}
