package qouteall.imm_ptl.core.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Occupancy for the receiving side's whole-voxel light field. */
final class PortalReceivingCell {
    private PortalReceivingCell() { }

    static boolean isOpen(BlockState state, BlockGetter world, BlockPos pos) {
        if (state.isAir()) return true;
        // A torch or lever occupies the air in front of its backing face. Closing
        // that entire voxel removes the face's transported light and ambient tint.
        // Admit only non-occluding, non-colliding decorations. The field cannot
        // resolve partial solid shapes, so slabs, fences, doors and transparent
        // solid blocks retain their previous conservative blocking behavior.
        return !state.canOcclude()
            && state.getFluidState().isEmpty()
            && state.getLightBlock(world, pos) == 0
            && state.getCollisionShape(world, pos).isEmpty();
    }
}
