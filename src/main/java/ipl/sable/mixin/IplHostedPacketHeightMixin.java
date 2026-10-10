package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplHostedPositionHeight;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The packet's raw hosted target uses storage bounds before the game-mode interaction runs. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class IplHostedPacketHeightMixin {
    @WrapOperation(
        method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"),
        require = 1
    )
    private int ipl$targetStorageMaximum(
        Level level, Operation<Integer> original, ServerboundUseItemOnPacket packet
    ) {
        return IplHostedPositionHeight.maximumForPosition(
            level, packet.getHitResult().getBlockPos(), original.call(level));
    }
}
