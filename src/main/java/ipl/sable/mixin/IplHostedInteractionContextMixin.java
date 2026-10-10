package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplWorldFrameContext;
import ipl.sable.diagnostics.IplIgnitionTrace;
import ipl.sable.diagnostics.IplIgnitionTraceFacts;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Preserve honest hosting-level identity for hosted interactions while mapping only explicit
 * parent-frame terrain coordinates required by legacy assembly/disassembly handlers.
 *
 * <p>The BE-tick and physics-actor arming sites cover code that runs from ticks — but a
 * right-click handler runs synchronously from the interaction packet: Simulated's physics
 * assembler {@code placeIntoWorld} (ground checks, {@code getChunk}, build-height reads,
 * disassembly block placement — all via {@code this.getLevel()} = the hosting void), the
 * swivel bearing's split-into-physics-body action, throttle grips. With the context armed
 * for the click, {@code IplHostedWorldFrameRouterMixin} routes every world-frame access of
 * those handlers to the ship's actual dimension — structurally, for every mod. This is what
 * replaces the per-mod assembler mixin.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class IplHostedInteractionContextMixin {

    @WrapMethod(method = "useItemOn")
    private InteractionResult ipl$armWorldFrameForHostedUse(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hitResult, Operation<InteractionResult> original
    ) {
        try (var trace = IplIgnitionTrace.begin(SERVER, player, hitResult, "server.game_mode.use_item_on");
             var facts = IplIgnitionTraceFacts.begin(player, hitResult)) {
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "game_mode.enter",
                "player_level", IplIgnitionTraceFacts.level(player.level()),
                "argument_level", IplIgnitionTraceFacts.level(level), "hand", hand,
                "secondary_use", player.isSecondaryUseActive(), "spectator", player.isSpectator());
            ServerLevel parent = level instanceof ServerLevel serverLevel
                ? IplWorldFrameContext.resolveParentForPlotInteraction(serverLevel, hitResult.getBlockPos())
                : null;
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "game_mode.frame",
                "resolved_parent", IplIgnitionTraceFacts.level(parent),
                "previous_frame", IplIgnitionTraceFacts.level(IplWorldFrameContext.current()));
            InteractionResult result;
            if (parent == null) {
                result = original.call(player, level, stack, hand, hitResult);
            } else {
                ServerLevel previous = IplWorldFrameContext.push(parent);
                try {
                    result = original.call(player, level, stack, hand, hitResult);
                } finally {
                    IplWorldFrameContext.pop(previous);
                }
            }
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "game_mode.return", "result", result);
            return result;
        }
    }
}
