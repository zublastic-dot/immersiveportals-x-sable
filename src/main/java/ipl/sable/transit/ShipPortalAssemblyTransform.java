package ipl.sable.transit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** The original assembly translation, retained across a later plot rehome. */
record ShipPortalAssemblyTransform(BlockPos worldAnchor, BlockPos sourcePlotCenter, AABB bounds) {
    ShipPortalAssemblyTransform {
        // Assembly callers may reuse mutable block positions after returning.
        worldAnchor = worldAnchor.immutable();
        sourcePlotCenter = sourcePlotCenter.immutable();
        bounds = new AABB(bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    BlockPos deltaTo(BlockPos finalPlotCenter) {
        // Rehome moves the plot's X/Z slot but copies block Y unchanged. The final
        // level's vertical center can differ from the center used by assembly.
        return new BlockPos(finalPlotCenter.getX() - worldAnchor.getX(),
            sourcePlotCenter.getY() - worldAnchor.getY(),
            finalPlotCenter.getZ() - worldAnchor.getZ());
    }
}
