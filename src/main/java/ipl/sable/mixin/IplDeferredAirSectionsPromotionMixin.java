package ipl.sable.mixin;

import ipl.sable.dim.IplDeferredAirSections;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Promotion must retain the same chunk-owned archive that was attached by the serializer. */
@Mixin(LevelChunk.class)
public abstract class IplDeferredAirSectionsPromotionMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At("RETURN"), require = 1)
    private void iplsable$promoteDeferredAirSections(
        ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor processor, CallbackInfo ci
    ) {
        IplDeferredAirSections.copy((IplDeferredAirSections) proto, (IplDeferredAirSections) this);
    }
}
