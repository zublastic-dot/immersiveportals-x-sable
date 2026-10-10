package ipl.sable.mixin.diagnostics.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.diagnostics.IplIgnitionTrace;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes the real use-key path, including clicks that never reach item use. */
@Mixin(Minecraft.class)
public abstract class IplMinecraftInteractionTraceMixin {
    @WrapMethod(method = "startUseItem()V", require = 1, allow = 1)
    private void ipl$traceUseClick(Operation<Void> original) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.player == null
            || !(client.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) {
            original.call();
            return;
        }

        try (IplIgnitionTrace.Scope ignored = IplIgnitionTrace.begin(
            IplIgnitionTrace.Side.CLIENT, client.player, hit, "minecraft.startUseItem"
        )) {
            if (IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.CLIENT)) {
                IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.click",
                    "destroying", client.gameMode != null && client.gameMode.isDestroying(),
                    "handsBusy", client.player.isHandsBusy(),
                    "pose", client.player.getPose(),
                    "secondaryUse", client.player.isSecondaryUseActive());
            }
            original.call();
            IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.click.return");
        }
    }

    @WrapOperation(
        method = "startUseItem()V",
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/client/ClientHooks;onClickInput(ILnet/minecraft/client/KeyMapping;"
                + "Lnet/minecraft/world/InteractionHand;)"
                + "Lnet/neoforged/neoforge/client/event/InputEvent$InteractionKeyMappingTriggered;"),
        require = 1, allow = 1
    )
    private InputEvent.InteractionKeyMappingTriggered ipl$traceClickEvent(
        int button, KeyMapping mapping, InteractionHand hand,
        Operation<InputEvent.InteractionKeyMappingTriggered> original
    ) {
        InputEvent.InteractionKeyMappingTriggered event = original.call(button, mapping, hand);
        if (IplIgnitionTrace.isTracing(IplIgnitionTrace.Side.CLIENT)) {
            IplIgnitionTrace.event(IplIgnitionTrace.Side.CLIENT, "client.click.event",
                "hand", hand, "cancelled", event.isCanceled(), "swing", event.shouldSwingHand());
        }
        return event;
    }
}
