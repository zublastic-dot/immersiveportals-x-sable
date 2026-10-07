package qouteall.imm_ptl.peripheral.mixin.common.nether_portal;

import ipl.sable.diagnostics.IplIgnitionTrace;
import ipl.sable.diagnostics.IplIgnitionTraceFacts;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.peripheral.PeripheralModMain;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;

@Mixin(FlintAndSteelItem.class)
public class MixinFlintAndSteelItem_CVB {
    @Inject(method = "Lnet/minecraft/world/item/FlintAndSteelItem;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void onUseFlintAndSteel(
        UseOnContext context,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        LevelAccessor world = context.getLevel();
        if (!world.isClientSide()) {
            BlockPos targetPos = context.getClickedPos();
            Direction side = context.getClickedFace();
            BlockPos firePos = targetPos.relative(side);
            BlockState targetBlockState = world.getBlockState(targetPos);
            Block targetBlock = targetBlockState.getBlock();
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.target",
                "level", IplIgnitionTraceFacts.level((Level) world), "pos", targetPos.toShortString(),
                "face", side, "fire_pos", firePos.toShortString(), "target_state", targetBlockState,
                "target_block", targetBlock);
            boolean glass = BreakableMirror.isGlass(((Level) world), targetPos);
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.mirror_gate",
                "glass", glass, "mirror_enabled", IPGlobal.enableMirrorCreation);
            if (glass && IPGlobal.enableMirrorCreation) {
                BreakableMirror mirror = BreakableMirror.createMirror(
                    ((ServerLevel) world), targetPos, side
                );
                if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.mirror_return",
                    "created", mirror != null, "result", InteractionResult.SUCCESS);
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
            else if (targetBlock == PeripheralModMain.portalHelperBlock) {
                boolean result = IntrinsicPortalGeneration.activatePortalHelper(
                    ((ServerLevel) world),
                    firePos
                );
                if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.helper_return",
                    "activated", result, "result", InteractionResult.SUCCESS);
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
            else if (targetBlock == Blocks.OBSIDIAN) {
                Player player = context.getPlayer();
                if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.obsidian",
                    "player_present", player != null, "pose", player == null ? null : player.getPose());
                if (player != null) {
                    if (player.getPose() == Pose.CROUCHING) {
                        boolean succeeded = IntrinsicPortalGeneration.onCrouchingPlayerIgnite(
                            ((ServerLevel) world),
                            ((ServerPlayer) player),
                            firePos
                        );
                        if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.crouching_ignite_return",
                            "succeeded", succeeded);
                        if (succeeded) {
                            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.ip_return",
                                "result", InteractionResult.SUCCESS, "branch", "crouching_ignite");
                            cir.setReturnValue(InteractionResult.SUCCESS);
                            return;
                        }
                    }
                    boolean succ = IntrinsicPortalGeneration.onFireLitOnObsidian(
                        ((ServerLevel) world),
                        firePos,
                        player
                    );
                    if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.portal_ignite_return",
                        "succeeded", succ);
                    if (succ) {
                        // it won't create the fire block
                        cir.setReturnValue(InteractionResult.SUCCESS);
                    }
                }
            }
            if (IplIgnitionTrace.isTracing(SERVER)) IplIgnitionTrace.event(SERVER, "flint.ip_exit",
                "canceled", cir.isCancelled(), "result", cir.isCancelled() ? cir.getReturnValue() : "vanilla_fallthrough");
        }
    }
}
