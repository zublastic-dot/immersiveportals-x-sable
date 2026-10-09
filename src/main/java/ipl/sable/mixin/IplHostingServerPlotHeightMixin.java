package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import ipl.sable.dim.IplChunkStorageHeight;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplPlotStorageMigration;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Sable persistence and lighting use the plot's fixed storage coordinates, never its parent frame. */
@Pseudo
@Mixin(value = ServerLevelPlot.class, remap = false)
public abstract class IplHostingServerPlotHeightMixin {
    @WrapOperation(
        method = "newNonLitChunk(Lnet/minecraft/world/level/ChunkPos;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I"),
        require = 1
    )
    private int ipl$loadStorageSections(Level level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getSectionsCount() : original.call(level);
    }

    @WrapOperation(
        method = "initializeLight(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionYFromSectionIndex(I)I"),
        require = 1
    )
    private int ipl$initialLightStorageY(Level level, int index, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getSectionYFromSectionIndex(index)
            : original.call(level, index);
    }

    @WrapOperation(
        method = "save()Lnet/minecraft/nbt/CompoundTag;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSectionYFromSectionIndex(I)I"),
        require = 1
    )
    private int ipl$saveLightStorageY(ServerLevel level, int index, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getSectionYFromSectionIndex(index)
            : original.call(level, index);
    }

    @WrapOperation(
        method = "load(Lnet/minecraft/nbt/CompoundTag;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSectionYFromSectionIndex(I)I"),
        require = 1
    )
    private int ipl$loadLightStorageY(ServerLevel level, int index, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getSectionYFromSectionIndex(index)
            : original.call(level, index);
    }

    @WrapOperation(
        method = "onRemoveChunkHolder(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinSection()I"),
        require = 1
    )
    private int ipl$removeStorageMinSection(ServerLevel level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getMinSection() : original.call(level);
    }

    @WrapOperation(
        method = "onRemoveChunkHolder(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxSection()I"),
        require = 1
    )
    private int ipl$removeStorageMaxSection(ServerLevel level, Operation<Integer> original) {
        return IplDimAgnostic.isHostingLevel(level)
            ? IplChunkStorageHeight.forChunk(level, -1).getMaxSection() : original.call(level);
    }

    @WrapMethod(method = "load(Lnet/minecraft/nbt/CompoundTag;)V", require = 1)
    private void ipl$loadStorageProfile(CompoundTag saved, Operation<Void> original) {
        ServerLevel level = ((ServerLevelPlot) (Object) this).getSubLevel().getLevel();
        if (!IplDimAgnostic.isHostingLevel(level)) {
            original.call(saved);
            return;
        }
        var storage = level.dimensionType();
        original.call(IplPlotStorageMigration.prepareForLoad(saved, storage.minY(), storage.height()));
    }

    @ModifyReturnValue(method = "save()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"), require = 1)
    private CompoundTag ipl$saveStorageProfile(CompoundTag saved) {
        ServerLevel level = ((ServerLevelPlot) (Object) this).getSubLevel().getLevel();
        if (!IplDimAgnostic.isHostingLevel(level)) return saved;
        var storage = level.dimensionType();
        return IplPlotStorageMigration.stampSave(saved, storage.minY(), storage.height());
    }
}
