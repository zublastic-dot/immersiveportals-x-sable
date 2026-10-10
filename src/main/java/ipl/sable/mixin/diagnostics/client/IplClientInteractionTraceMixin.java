package ipl.sable.mixin.diagnostics.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.diagnostics.IplIgnitionTrace;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps the actual item-use result and preserves every existing guard. */
@Mixin(MultiPlayerGameMode.class)
public abstract class IplClientInteractionTraceMixin {
    @WrapMethod(method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;"
        + "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)"
        + "Lnet/minecraft/world/InteractionResult;", require = 1, allow = 1)
    private InteractionResult ipl$traceUseItemOn(
        LocalPlayer player, InteractionHand hand, BlockHitResult hit,
        Operation<InteractionResult> original
    ) {
        try (IplIgnitionTrace.Scope ignored = IplIgnitionTrace.begin(
            IplIgnitionTrace.Side.CLIENT, player, hit, "multiplayer.useItemOn"
        )) {
            if (IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.CLIENT)) {
                IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.useItemOn",
                    "hand", hand,
                    "item", BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()),
                    "hit", hit.getBlockPos(), "face", hit.getDirection(),
                    "hitLocation", hit.getLocation());
            }
            InteractionResult result = original.call(player, hand, hit);
            IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.useItemOn.return",
                "result", result);
            return result;
        }
    }
}
