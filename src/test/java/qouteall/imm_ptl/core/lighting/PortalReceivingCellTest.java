package qouteall.imm_ptl.core.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightSnapshot.*;

class PortalReceivingCellTest {
    @BeforeAll static void bootstrap() { PortalBlockTestBootstrap.initialize(); }

    private static boolean open(BlockState state) {
        return PortalReceivingCell.isOpen(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Test void nonCollidingAttachmentsDoNotReplaceTheAirBesideTheirBackingFace() {
        for (Block block : new Block[]{Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH,
            Blocks.SOUL_WALL_TORCH, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.LEVER, Blocks.STONE_BUTTON}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                assertFalse(state.isAir(), state::toString);
                assertTrue(open(state), state::toString);
            }
        }
    }

    @Test void solidsPartialShapesFluidsAndTheLocalPortalBarrierStayClosed() {
        for (Block block : new Block[]{Blocks.WHITE_WOOL, Blocks.OBSIDIAN, Blocks.GLASS,
            Blocks.OAK_SLAB, Blocks.OAK_STAIRS, Blocks.OAK_FENCE, Blocks.OAK_TRAPDOOR,
            Blocks.WHITE_CARPET, Blocks.WATER, Blocks.LAVA, PortalPlaceholderBlock.instance}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates())
                assertFalse(open(state), state::toString);
        }
        for (Block air : new Block[]{Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR})
            assertTrue(open(air.defaultBlockState()));
    }

    private static Update corridor(BlockState middle, Snapshot previous) {
        Pos attached = new Pos(2, 0, 0);
        return update(p -> {
            BlockState state = p.equals(attached) ? middle
                : p.y() == 0 && p.z() == 0 && p.x() >= 1 && p.x() <= 3
                ? Blocks.AIR.defaultBlockState() : Blocks.WHITE_WOOL.defaultBlockState();
            return new Sample(open(state) ? Cell.OPEN : Cell.CLOSED, new Light(0, 0));
        }, p -> new Sample(Cell.OPEN, new Light(15, 8)),
            Map.of(new Pos(1, 0, 0), new Pos(100, 0, 0)), new Pos(1, 0, 0), previous);
    }

    @Test void placingWallTorchPreservesItsBackingFacesLightAndSolidReplacementBlocksAgain() {
        var air = corridor(Blocks.AIR.defaultBlockState(), null);
        assertTrue(air.field().available());
        var torch = corridor(Blocks.REDSTONE_WALL_TORCH.defaultBlockState(), air.snapshot());
        assertEquals(air.field(), torch.field(), "A decoration must not punch a square hole in the field");
        assertEquals(new Light(13, 6), torch.field().cells().get(new Pos(2, 0, 0)));
        assertEquals(1f, torch.field().replacement().get(new Pos(2, 0, 0)));
        var wall = corridor(Blocks.WHITE_WOOL.defaultBlockState(), torch.snapshot());
        assertFalse(wall.field().cells().containsKey(new Pos(2, 0, 0)));
        assertFalse(wall.field().cells().containsKey(new Pos(3, 0, 0)), "The solid wall must block the cell behind it");
        var restored = corridor(Blocks.SOUL_WALL_TORCH.defaultBlockState(), wall.snapshot());
        assertEquals(air.field(), restored.field());
    }
}
