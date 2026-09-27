package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

@Mixin(LevelRenderer.class)
public class MixinPortalSurfaceOutline {
    @Inject(
        method = "renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void ip_hidePortalSurfaceOutline(
        PoseStack poses, VertexConsumer vertices, Entity entity,
        double cameraX, double cameraY, double cameraZ,
        BlockPos position, BlockState state, CallbackInfo ci
    ) {
        // Use the renderer's resolved state: a Sable target may be in plot
        // coordinates, and a nested view may belong to another dimension.
        // Suppress only drawing, never the hit result, shape or interaction.
        if (!IPConfig.getConfig().showPortalSurfaceOutline
            && state.is(PortalPlaceholderBlock.instance)) {
            ci.cancel();
        }
    }
}
