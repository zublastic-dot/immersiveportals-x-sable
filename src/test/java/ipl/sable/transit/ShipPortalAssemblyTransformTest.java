package ipl.sable.transit;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShipPortalAssemblyTransformTest {
    private static final AABB BOUNDS = new AABB(37954, 140, -61946, 37959, 149, -61944);

    @Test void retainsTheSourceHeightForTheReportedEightyBlockMismatch() {
        BlockPos anchor = new BlockPos(37956, 145, -61945);
        BlockPos sourceCenter = new BlockPos(20481032, 208, 20503558);
        BlockPos hostedCenter = new BlockPos(sourceCenter.getX(), 128, sourceCenter.getZ());
        var transform = new ShipPortalAssemblyTransform(anchor, sourceCenter, BOUNDS);
        Vec3 aperture = new Vec3(37956.5, 144.5, -61945.5);
        BlockPos delta = transform.deltaTo(hostedCenter);

        Vec3 mapped = aperture.add(delta.getX(), delta.getY(), delta.getZ());
        assertEquals(207.5, mapped.y);
        assertEquals(127.5, aperture.y + hostedCenter.getY() - anchor.getY(),
            "The previous implementation misplaced this aperture by exactly 80 blocks");
        assertMatchesAssemblyAndRehome(anchor, sourceCenter, hostedCenter, aperture);
    }

    @Test void shapeAndApertureMatchSablesAssemblyFollowedByOnlyHorizontalRehome() {
        BlockPos anchor = new BlockPos(-321, -45, 77);
        // Different dimension heights, including negative vertical plot centers.
        for (int sourceY : new int[]{208, 128, -96}) {
            for (int hostingY : new int[]{128, 208, -160}) {
                BlockPos sourceCenter = new BlockPos(20481032, sourceY, 20503558);
                for (BlockPos target : List.of(
                    new BlockPos(sourceCenter.getX(), hostingY, sourceCenter.getZ()),
                    new BlockPos(sourceCenter.getX() + 2048, hostingY, sourceCenter.getZ() - 4096))) {
                    assertMatchesAssemblyAndRehome(anchor, sourceCenter, target,
                        new Vec3(-320.5, -47.5, 77));
                }
            }
        }
    }

    @Test void immediateHostedCapturePreservesTheOrdinaryAssemblyTranslation() {
        BlockPos anchor = new BlockPos(5, 72, -9);
        BlockPos center = new BlockPos(20480008, 128, 20480008);
        assertMatchesAssemblyAndRehome(anchor, center, center, new Vec3(5.5, 70.5, -9));
    }

    @Test void pendingCaptureDoesNotFollowMutableCallerPositions() {
        var anchor = new BlockPos.MutableBlockPos(10, 144, -20);
        var center = new BlockPos.MutableBlockPos(20480008, 208, 20480008);
        var transform = new ShipPortalAssemblyTransform(anchor, center, BOUNDS);
        anchor.set(900, 700, 800);
        center.set(0, 128, 0);

        assertEquals(new BlockPos(10, 144, -20), transform.worldAnchor());
        assertEquals(new BlockPos(20480008, 208, 20480008), transform.sourcePlotCenter());
        assertNotSame(BOUNDS, transform.bounds());
        assertEquals(BOUNDS, transform.bounds());
        assertEquals(new BlockPos(20479998, 64, 20480028),
            transform.deltaTo(new BlockPos(20480008, 128, 20480008)));
    }

    private static void assertMatchesAssemblyAndRehome(
        BlockPos anchor, BlockPos sourceCenter, BlockPos finalCenter, Vec3 aperture
    ) {
        // Use Sable's production assembly transform as the reference, then apply
        // the slot move performed by SableRehomeOps (block Y is copied verbatim).
        var assembly = new SubLevelAssemblyHelper.AssemblyTransform(
            anchor, sourceCenter, 0, Rotation.NONE, null);
        int dx = finalCenter.getX() - sourceCenter.getX();
        int dz = finalCenter.getZ() - sourceCenter.getZ();
        var captured = new ShipPortalAssemblyTransform(anchor, sourceCenter, BOUNDS);
        BlockPos delta = captured.deltaTo(finalCenter);
        Vec3 expectedAperture = assembly.apply(aperture).add(dx, 0, dz);
        Vec3 actualAperture = aperture.add(delta.getX(), delta.getY(), delta.getZ());
        assertTrue(expectedAperture.distanceToSqr(actualAperture) < 1e-16);

        // Frame and opening blocks must receive the SAME translation as the
        // continuous aperture; otherwise the portal will fail integrity checks.
        for (BlockPos block : List.of(anchor.below(3), anchor.above(), anchor.offset(2, -1, 0))) {
            assertEquals(assembly.apply(block).offset(dx, 0, dz), block.offset(delta));
        }
    }
}
