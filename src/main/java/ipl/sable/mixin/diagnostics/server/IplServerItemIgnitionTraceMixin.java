package ipl.sable.mixin.diagnostics.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.diagnostics.IplIgnitionTrace;
import ipl.sable.diagnostics.IplIgnitionTraceFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;

/** Observe the actual block, event and item results, without replaying any lookup or callback. */
@Mixin(ServerPlayerGameMode.class)
public abstract class IplServerItemIgnitionTraceMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1)
    private BlockState ipl$actualTarget(Level level, BlockPos pos, Operation<BlockState> original) {
        BlockState result = original.call(level, pos);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.target_state",
            "level", IplIgnitionTraceFacts.level(level), "pos", pos.toShortString(), "state", result);
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/block/Block;isEnabled(Lnet/minecraft/world/flag/FeatureFlagSet;)Z"), require = 1)
    private boolean ipl$blockEnabled(Block block, FeatureFlagSet flags, Operation<Boolean> original) {
        boolean result = original.call(block, flags);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.block_enabled", "result", result);
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/neoforged/neoforge/common/CommonHooks;onRightClickBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock;"), require = 1)
    private PlayerInteractEvent.RightClickBlock ipl$rightClickEvent(Player player, InteractionHand hand,
        BlockPos pos, BlockHitResult hit, Operation<PlayerInteractEvent.RightClickBlock> original) {
        var result = original.call(player, hand, pos, hit);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.right_click_event",
            "canceled", result.isCanceled(), "cancellation_result", result.getCancellationResult(),
            "use_block", result.getUseBlock(), "use_item", result.getUseItem());
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/item/ItemStack;onItemUseFirst(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private InteractionResult ipl$useFirst(ItemStack stack, UseOnContext context, Operation<InteractionResult> original) {
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.use_first_enter",
            "effective_level", IplIgnitionTraceFacts.level(context.getLevel()), "pos", context.getClickedPos().toShortString());
        InteractionResult result = original.call(stack, context);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.use_first_return", "result", result);
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/block/state/BlockState;useItemOn(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;"), require = 1)
    private ItemInteractionResult ipl$blockWithItem(BlockState state, ItemStack stack, Level level,
        Player player, InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> original) {
        var result = original.call(state, stack, level, player, hand, hit);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.block_use_return", "result", result);
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/block/state/BlockState;useWithoutItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"), require = 1)
    private InteractionResult ipl$blockWithoutItem(BlockState state, Level level, Player player,
        BlockHitResult hit, Operation<InteractionResult> original) {
        InteractionResult result = original.call(state, level, player, hit);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.default_block_use_return", "result", result);
        return result;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"), require = 2)
    private InteractionResult ipl$itemUse(ItemStack stack, UseOnContext context, Operation<InteractionResult> original) {
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.use_on_enter",
            "effective_level", IplIgnitionTraceFacts.level(context.getLevel()),
            "pos", context.getClickedPos().toShortString(), "face", context.getClickedFace());
        InteractionResult result = original.call(stack, context);
        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "item.use_on_return", "result", result);
        return result;
    }
}
