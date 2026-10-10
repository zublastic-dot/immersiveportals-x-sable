package ipl.sable.mixin;

import ipl.sable.dim.IplChunkStorageHeight;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep light masks and padding in storage space when a parent interaction changes world bounds. */
@Mixin(LevelLightEngine.class)
public abstract class IplHostingLightHeightMixin {
    @Shadow @Final @Mutable protected LevelHeightAccessor levelHeightAccessor;

    @Inject(
        method = "<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;levelHeightAccessor:Lnet/minecraft/world/level/LevelHeightAccessor;",
            opcode = 181, shift = At.Shift.AFTER, unsafe = true), require = 1
    )
    private void ipl$pinLightStorageHeight(LightChunkGetter source, boolean block, boolean sky, CallbackInfo ci) {
        levelHeightAccessor = IplChunkStorageHeight.forChunk(levelHeightAccessor, -1);
    }
}
