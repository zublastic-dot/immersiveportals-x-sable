package qouteall.imm_ptl.core.portal;

import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class PortalBlockTestBootstrap {
    private PortalBlockTestBootstrap() { }

    public static synchronized void initialize() {
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var id = ResourceLocation.fromNamespaceAndPath("immersive_portals", "nether_portal_block");
        if (BuiltInRegistries.BLOCK.containsKey(id)) return;
        // Unit tests do not fire mod registration events.
        var registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        registry.unfreeze();
        Registry.register(registry, id, PortalPlaceholderBlock.instance);
        PortalPlaceholderBlock.instance.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
        registry.freeze();
    }
}
