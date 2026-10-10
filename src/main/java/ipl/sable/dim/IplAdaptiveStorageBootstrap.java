package ipl.sable.dim;

import ipl.sable.mixin.IplDimensionRegistrationInfoAccessor;
import ipl.sable.mixin.IplDimensionTypeHeightAccessor;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static ipl.sable.dim.IplAdaptiveStorageProfile.Bounds;

/** Server-authoritative bounds, fixed before the first level or synchronized registry packet exists. */
public final class IplAdaptiveStorageBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-storage");

    private IplAdaptiveStorageBootstrap() {}

    public static void initialize(MinecraftServer server) {
        if (server.getAllLevels().iterator().hasNext()) {
            throw new IllegalStateException("Hosting storage profile must be selected before any level is allocated");
        }
        Registry<LevelStem> dimensions = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        Registry<DimensionType> types = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        ResourceKey<LevelStem> hostingKey = ResourceKey.create(Registries.LEVEL_STEM, SableSubLevelDimension.SUBLEVELS.location());
        ResourceKey<DimensionType> typeKey = ResourceKey.create(Registries.DIMENSION_TYPE, hostingKey.location());
        LevelStem hosting = dimensions.get(hostingKey);
        if (hosting == null) throw new IllegalStateException("Missing Sable hosting dimension " + hostingKey.location());
        DimensionType storageType = hosting.type().value();
        if (!hosting.type().is(typeKey) || types.get(typeKey) != storageType) {
            throw new IllegalStateException("Sable hosting requires its dedicated dimension type " + typeKey.location());
        }

        Bounds parents = null;
        int parentCount = 0;
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : dimensions.entrySet()) {
            if (entry.getKey().equals(hostingKey)) continue;
            DimensionType type = entry.getValue().type().value();
            if (type == storageType) {
                throw new IllegalStateException("Ordinary dimension shares the Sable storage type: " + entry.getKey().location());
            }
            Bounds bounds = new Bounds(type.minY(), type.height());
            parents = parents == null ? bounds : parents.union(bounds);
            parentCount++;
        }
        if (parents == null) throw new IllegalStateException("No parent dimensions available for Sable storage");

        // Resolve both accessor contracts before creating a persistent profile.
        IplDimensionTypeHeightAccessor mutable = (IplDimensionTypeHeightAccessor) (Object) storageType;
        Map<ResourceKey<?>, RegistrationInfo> registrationInfos =
            ((IplDimensionRegistrationInfoAccessor) types).iplsable$getStorageRegistrationInfos();
        if (!registrationInfos.containsKey(typeKey)) throw new IllegalStateException("Missing hosting type registration metadata");

        Path folder = DimensionType.getStorageFolder(SableSubLevelDimension.SUBLEVELS, server.getWorldPath(LevelResource.ROOT));
        try {
            LOG.info("[IPL-SABLE-STORAGE] resolving startup profile after dimension modifiers: parentDimensions={} parentBounds={}",
                parentCount, parents);
            long scanStarted = System.nanoTime();
            Optional<Bounds> previous = IplAdaptiveStorageProfile.read(folder);
            Optional<Bounds> saved = IplSavedPlotHeightScanner.scan(folder);
            Bounds selected = IplAdaptiveStorageProfile.select(parents, saved, previous);
            IplAdaptiveStorageProfile.persist(folder, selected);
            mutable.iplsable$setStorageMinY(selected.minY());
            mutable.iplsable$setStorageHeight(selected.height());
            mutable.iplsable$setStorageLogicalHeight(selected.height());
            clearKnownPack(registrationInfos, typeKey);
            LOG.info("[IPL-SABLE-STORAGE] profile selected before level allocation: minY={} maxY={} sections={} "
                    + "parentDimensions={} parentBounds={} savedBounds={} previousProfile={} preparationMs={} registryPayload=explicit",
                selected.minY(), selected.maxY(), selected.sectionCount(), parentCount, parents,
                saved.orElse(null), previous.orElse(null), (System.nanoTime() - scanStarted) / 1_000_000L);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot safely select hosting storage profile at " + folder, failure);
        }
    }

    static void clearKnownPack(Map<ResourceKey<?>, RegistrationInfo> registrations, ResourceKey<DimensionType> key) {
        RegistrationInfo previous = registrations.get(key);
        if (previous == null) throw new IllegalStateException("Missing hosting type registration metadata");
        // Known-pack omission would make clients reload the unmodified datapack JSON instead.
        registrations.put(key, new RegistrationInfo(Optional.empty(), previous.lifecycle()));
    }
}
