package ipl.sable.transit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class PortalVolumeCollisionTest {
    @BeforeAll static void bootstrapBlocks() {
        net.neoforged.fml.loading.LoadingModList.of(
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Unit tests have no mod-registration event. Register the real placeholder
        // after vanilla bootstrap, following NeoForge's unfreeze/register/freeze cycle.
        var registry = (net.minecraft.core.MappedRegistry<net.minecraft.world.level.block.Block>)
            net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        registry.unfreeze();
        net.minecraft.core.Registry.register(registry,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("immersive_portals", "nether_portal_block"),
            PortalPlaceholderBlock.instance);
        PortalPlaceholderBlock.instance.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
        registry.freeze();
    }

    @Test void lightingALargeFrameMustNotFillItsOpeningWithTransitVolume() {
        for (var axis : Direction.Axis.values()) {
            BlockState surface = PortalPlaceholderBlock.instance.defaultBlockState()
                .setValue(PortalPlaceholderBlock.AXIS, axis);
            assertTrue(surface.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO).isEmpty(), "The aperture has no physical block collision");
            var min = new BlockPos(-5, -6, 0); var max = new BlockPos(5, 6, 0);
            var unlit = IplPortalVolumeCache.collectBlocks(min, max, p ->
                rim(p) ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
            var lit = IplPortalVolumeCache.collectBlocks(min, max, p ->
                rim(p) ? Blocks.OBSIDIAN.defaultBlockState() : surface);
            assertEquals(44, unlit.size());
            assertEquals(new HashSet<>(unlit), new HashSet<>(lit),
                "A smaller frame inside this opening must not admit the large carrier through its portal");
        }
    }

    private static boolean rim(BlockPos p) {
        return Math.abs(p.getX()) == 5 || Math.abs(p.getY()) == 6;
    }

    @Test void aPortalSurfaceAloneDoesNotCountAsASolidCrossingBody() {
        assertTrue(IplPortalVolumeCache.collectBlocks(BlockPos.ZERO, new BlockPos(2, 3, 0),
            p -> PortalPlaceholderBlock.instance.defaultBlockState()).isEmpty());
    }

    @Test void ordinaryCarriedBlocksIncludingDecorationsKeepTheirExistingVolume() {
        var blocks = new BlockState[]{Blocks.OBSIDIAN.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
            Blocks.AIR.defaultBlockState()};
        var result = IplPortalVolumeCache.collectBlocks(BlockPos.ZERO, new BlockPos(3, 0, 0),
            p -> blocks[p.getX()]);
        assertEquals(java.util.List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)), result);
    }
}
