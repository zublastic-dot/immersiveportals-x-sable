package ipl.sable.mixin.diagnostics.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.diagnostics.IplIgnitionTrace;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes the final packet after IP redirection and NeoForge validation. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class IplClientPacketTraceMixin {
    @WrapOperation(
        method = "send(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 1, allow = 1
    )
    private void ipl$tracePacketSend(Connection connection, Packet<?> packet, Operation<Void> original) {
        boolean tracing = IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.CLIENT);
        if (tracing) {
            IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.packet.send",
                "packetType", packet.getClass().getName());
            if (packet instanceof ServerboundUseItemOnPacket use) {
                IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.packet.useItemOn",
                    "sequence", use.getSequence(), "hand", use.getHand(),
                    "hit", use.getHitResult().getBlockPos(),
                    "face", use.getHitResult().getDirection());
            } else if (packet instanceof ServerboundCustomPayloadPacket custom) {
                IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.packet.customPayload",
                    "payloadType", custom.payload().type().id());
            }
        }
        original.call(connection, packet);
        // This confirms handing the packet to Connection; server receipt is a separate event.
        if (tracing) {
            IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.packet.send.return");
        }
    }
}
