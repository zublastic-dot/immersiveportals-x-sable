package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplDeferredAirSections;
import ipl.sable.dim.IplHostingChunkStorageMigration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla Anvil chunks in the hosting dimension share its adaptive storage profile. */
@Mixin(ChunkSerializer.class)
public abstract class IplHostingChunkSerializationMixin {
    // Replace the argument itself so RETURN hooks (including ScalableLux) see invalidated caches.
    @ModifyVariable(method = "read", at = @At("HEAD"), argsOnly = true, index = 4, require = 1)
    private static CompoundTag iplsable$migrateHostingChunk(
        CompoundTag saved, ServerLevel level, PoiManager poi, RegionStorageInfo storage, ChunkPos pos, CompoundTag original
    ) {
        if (!IplDimAgnostic.isHostingLevel(level)) return saved;
        var profile = level.dimensionType();
        return IplHostingChunkStorageMigration.prepareForLoad(saved, profile.minY(), profile.height());
    }

    @Inject(method = "read", at = @At("RETURN"), require = 1)
    private static void iplsable$retainDeferredAirSections(
        ServerLevel level, PoiManager poi, RegionStorageInfo storage, ChunkPos pos, CompoundTag saved,
        CallbackInfoReturnable<ProtoChunk> cir
    ) {
        if (!IplDimAgnostic.isHostingLevel(level)) return;
        IplDeferredAirSections.attach(cir.getReturnValue(), saved);
    }

    @ModifyReturnValue(method = "write", at = @At("RETURN"), require = 1)
    private static CompoundTag iplsable$stampHostingChunk(CompoundTag saved, ServerLevel level, ChunkAccess chunk) {
        if (!IplDimAgnostic.isHostingLevel(level)) return saved;
        var profile = level.dimensionType();
        IplDeferredAirSections.write(chunk, saved);
        return IplHostingChunkStorageMigration.stampOwnedSave(saved, profile.minY(), profile.height());
    }
}
