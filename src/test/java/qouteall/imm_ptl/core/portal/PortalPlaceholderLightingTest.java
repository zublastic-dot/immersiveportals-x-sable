package qouteall.imm_ptl.core.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class PortalPlaceholderLightingTest {
    @BeforeAll static void bootstrap() { PortalBlockTestBootstrap.initialize(); }

    @Test void invisibleNonCollidingSurfaceBlocksLightOnEveryAxis() {
        var world = new Fixture();
        for (var axis : Direction.Axis.values()) {
            BlockState state = PortalPlaceholderBlock.instance.defaultBlockState().setValue(PortalPlaceholderBlock.AXIS, axis);
            assertEquals(15, state.getLightBlock(world, BlockPos.ZERO));
            assertEquals(0, state.getLightEmission(world, BlockPos.ZERO));
            assertFalse(state.propagatesSkylightDown(world, BlockPos.ZERO));
            assertEquals(RenderShape.INVISIBLE, state.getRenderShape());
            assertFalse(state.canOcclude(), "Do not hide adjacent geometry or make the portal an AO wall");
            assertTrue(state.getCollisionShape(world, BlockPos.ZERO).isEmpty());
            assertEquals(1.0F, state.getShadeBrightness(world, BlockPos.ZERO));
        }
    }

    @Test void realEngineReproducesMeasuredRoofPathAndBlocksItAtPortal() {
        Fixture old = room(false), fixed = room(true);
        BlockPos source = new BlockPos(5, 11, 8);
        old.put(source, Blocks.GLOWSTONE.defaultBlockState()); fixed.put(source, Blocks.GLOWSTONE.defaultBlockState());
        old.light(); fixed.light();
        // Same geometry/attenuation as the live glowstone -> roof edge -> aperture -> room scan.
        assertEquals(15, old.at(5, 11, 8)); assertEquals(14, old.at(4, 11, 8));
        assertEquals(13, old.at(3, 11, 8)); assertEquals(12, old.at(3, 10, 8));
        assertEquals(11, old.at(3, 9, 8)); assertEquals(10, old.at(3, 8, 8));
        assertEquals(9, old.at(4, 8, 8)); assertEquals(8, old.at(5, 8, 8));
        assertEquals(0, old.at(5, 10, 8)); assertEquals(0, old.at(5, 9, 8));
        assertEquals(10, fixed.at(3, 8, 8), "Exterior source must remain lit");
        assertEquals(0, fixed.at(4, 8, 8)); assertEquals(0, fixed.at(5, 8, 8));
    }

    @Test void ordinaryEmittersAllRespectTheBoundary() {
        for (var source : new BlockState[]{Blocks.GLOWSTONE.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
            Blocks.SEA_LANTERN.defaultBlockState(), Blocks.REDSTONE_TORCH.defaultBlockState()}) {
            Fixture old = room(false), fixed = room(true);
            BlockPos position = new BlockPos(3, 6, 8);
            old.put(position, source); fixed.put(position, source); old.light(); fixed.light();
            assertTrue(old.at(5, 6, 8) > 0, "Negative control must leak for " + source);
            assertEquals(0, fixed.at(5, 6, 8), "Boundary failed for " + source);
            assertEquals(source.getLightEmission(fixed, position), fixed.at(3, 6, 8));
        }
    }

    @Test void checkingExistingApertureCellsRemovesAlreadyPropagatedLight() {
        Fixture world = room(false); world.put(new BlockPos(5, 11, 8), Blocks.GLOWSTONE.defaultBlockState()); world.light();
        assertEquals(8, world.at(5, 8, 8));
        var aperture = BlockPos.betweenClosedStream(4, 3, 3, 4, 8, 12).map(BlockPos::immutable).toList();
        aperture.forEach(pos -> world.put(pos, PortalPlaceholderBlock.instance.defaultBlockState()));
        var checks = new PendingLightChecks<>(aperture);
        checks.drain(64, pos -> { world.engine.checkBlock(pos); return true; });
        world.settle();
        assertEquals(0, world.at(5, 8, 8), "Old light must be removed without replacing the world or loading chunks");
        assertEquals(15, world.at(5, 11, 8));
    }

    @Test void interiorSourcesAndRemovedPortalsStillLightNormally() {
        Fixture world = room(true); world.put(new BlockPos(7, 4, 8), Blocks.GLOWSTONE.defaultBlockState()); world.light();
        assertEquals(13, world.at(5, 4, 8));
        assertEquals(0, world.at(3, 4, 8));
        BlockPos removed = new BlockPos(4, 4, 8);
        world.put(removed, Blocks.AIR.defaultBlockState()); world.engine.checkBlock(removed); world.settle();
        assertEquals(11, world.at(3, 4, 8), "Removing a portal restores ordinary light through the opening");
    }

    @Test void horizontalPortalStopsDirectSkyColumn() {
        Fixture world = new Fixture();
        var skylight = new ChunkSkyLightSources(world);
        world.put(new BlockPos(5, 9, 8), PortalPlaceholderBlock.instance.defaultBlockState().setValue(PortalPlaceholderBlock.AXIS, Direction.Axis.Y));
        assertTrue(skylight.update(world, 5, 9, 8));
        assertEquals(10, skylight.getLowestSourceY(5, 8));
        world.put(new BlockPos(5, 9, 8), Blocks.AIR.defaultBlockState());
        assertTrue(skylight.update(world, 5, 9, 8));
        assertEquals(ChunkSkyLightSources.NEGATIVE_INFINITY, skylight.getLowestSourceY(5, 8));
    }

    private static Fixture room(boolean sealedPortal) {
        Fixture world = new Fixture();
        for (BlockPos pos : BlockPos.betweenClosed(4, 2, 2, 9, 9, 13)) {
            if (pos.getX() == 4 || pos.getX() == 9 || pos.getY() == 2 || pos.getY() == 9 || pos.getZ() == 2 || pos.getZ() == 13)
                world.put(pos, Blocks.WHITE_WOOL.defaultBlockState());
        }
        for (BlockPos pos : BlockPos.betweenClosed(4, 3, 3, 4, 8, 12))
            world.put(pos, sealedPortal ? PortalPlaceholderBlock.instance.defaultBlockState() : Blocks.AIR.defaultBlockState());
        for (BlockPos pos : BlockPos.betweenClosed(4, 10, 2, 9, 10, 13)) world.put(pos, Blocks.WHITE_CONCRETE.defaultBlockState());
        return world;
    }

    /** Real Minecraft propagation, with only the loaded block/chunk provider replaced. */
    private static final class Fixture implements LightChunk, LightChunkGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final BlockLightEngine engine = new BlockLightEngine(this);
        void put(BlockPos pos, BlockState state) { blocks.put(pos.immutable(), state); }
        int at(int x, int y, int z) { return engine.getLightValue(new BlockPos(x, y, z)); }
        void light() {
            engine.updateSectionStatus(SectionPos.of(0, 0, 0), false); settle();
            engine.propagateLightSources(new ChunkPos(0, 0)); settle();
        }
        void settle() {
            int runs = 0;
            while (engine.hasLightWork()) { assertTrue(++runs < 100, "Light engine did not settle"); engine.runLightUpdates(); }
        }
        @Override public LightChunk getChunkForLighting(int x, int z) { return x == 0 && z == 0 ? this : null; }
        @Override public BlockGetter getLevel() { return this; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 16; }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public ChunkSkyLightSources getSkyLightSources() { return null; }
        @Override public void findBlockLightSources(BiConsumer<BlockPos, BlockState> output) {
            blocks.forEach((pos, state) -> { if (state.getLightEmission(this, pos) > 0) output.accept(pos, state); });
        }
    }
}
