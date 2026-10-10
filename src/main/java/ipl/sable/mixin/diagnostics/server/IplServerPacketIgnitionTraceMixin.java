package ipl.sable.mixin.diagnostics.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.diagnostics.IplIgnitionTrace;
import ipl.sable.diagnostics.IplIgnitionTraceFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;

/** Captures real server decisions. The network-thread enqueue does not open an attempt. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class IplServerPacketIgnitionTraceMixin {
    @Shadow public ServerPlayer player;
    @Unique private final ThreadLocal<IplIgnitionTrace.Scope> ipl$ignitionPacketScope = new ThreadLocal<>();

    @WrapMethod(method = "handleUseItemOn")
    private void ipl$closePacketTrace(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        IplIgnitionTrace.Scope previous = ipl$ignitionPacketScope.get();
        ipl$ignitionPacketScope.remove();
        try { original.call(packet); }
        finally {
            IplIgnitionTrace.Scope scope = ipl$ignitionPacketScope.get();
            try { if (scope != null) scope.close(); }
            finally {
                if (previous == null) ipl$ignitionPacketScope.remove();
                else ipl$ignitionPacketScope.set(previous);
            }
        }
    }

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER), require = 1)
    private void ipl$beginPacketAfterThreadGate(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        var trace = IplIgnitionTrace.begin(SERVER, player, packet.getHitResult(), "server.packet.use_item_on");
        if (!IplIgnitionTrace.isTracing(SERVER)) { trace.close(); return; }
        var facts = IplIgnitionTraceFacts.begin(player, packet.getHitResult());
        ipl$ignitionPacketScope.set(() -> { try { facts.close(); } finally { trace.close(); } });
        if (IplIgnitionTrace.isTracing(SERVER)) {
            IplIgnitionTrace.event(SERVER, "packet.received", "sequence", packet.getSequence(),
                "hand", packet.getHand(), "player_level", IplIgnitionTraceFacts.level(player.level()),
                "hit_location", packet.getHitResult().getLocation());
        }
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/item/ItemStack;isItemEnabled(Lnet/minecraft/world/flag/FeatureFlagSet;)Z"), require = 1)
    private boolean ipl$itemEnabled(ItemStack stack, FeatureFlagSet flags, Operation<Boolean> original) {
        boolean result = original.call(stack, flags);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.item_enabled", "result", result);
        return result;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/server/level/ServerPlayer;canInteractWithBlock(Lnet/minecraft/core/BlockPos;D)Z"), require = 1)
    private boolean ipl$canInteract(ServerPlayer receiver, BlockPos pos, double padding, Operation<Boolean> original) {
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.reach_enter",
            "level", IplIgnitionTraceFacts.level(receiver.level()), "pos", pos.toShortString(),
            "eye", receiver.getEyePosition(), "padding", padding, "range", receiver.blockInteractionRange());
        boolean result = original.call(receiver, pos, padding);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.can_interact", "result", result);
        return result;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/phys/Vec3;subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 ipl$hitOffset(Vec3 hit, Vec3 center, Operation<Vec3> original) {
        Vec3 offset = original.call(hit, center);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.hit_offset",
            "offset", offset, "component_limit_exclusive", 1.0000001);
        return offset;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"), require = 1)
    private int ipl$buildHeight(Level level, Operation<Integer> original, ServerboundUseItemOnPacket packet) {
        int result = original.call(level);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.build_height",
            "level", IplIgnitionTraceFacts.level(level), "maximum_exclusive", result,
            "hit_y", packet.getHitResult().getBlockPos().getY());
        return result;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target =
        "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;awaitingPositionFromClient:Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private Vec3 ipl$awaitingTeleport(ServerGamePacketListenerImpl receiver, Operation<Vec3> original) {
        Vec3 result = original.call(receiver);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.awaiting_teleport", "position", result);
        return result;
    }

    @WrapOperation(method = "handleUseItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/server/level/ServerLevel;mayInteract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean ipl$mayInteract(ServerLevel level, Player receiver, BlockPos pos, Operation<Boolean> original) {
        boolean result = original.call(level, receiver, pos);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "packet.may_interact",
            "level", IplIgnitionTraceFacts.level(level), "pos", pos.toShortString(), "result", result);
        return result;
    }
}
