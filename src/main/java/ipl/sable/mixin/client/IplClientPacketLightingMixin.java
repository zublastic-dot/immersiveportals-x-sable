package ipl.sable.mixin.client;

import ipl.sable.dim.IplLightChunkOwnership;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class IplClientPacketLightingMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "enableChunkLight", at = @At("HEAD"), cancellable = true, require = 1)
    private void ipl$ignoreForeignChunkLight(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        // Packet masks use this listener world's min section, not the hosted world's.
        // Its own dimension-tagged packet will enable the hosted chunk's light.
        if (!IplLightChunkOwnership.belongsTo(level, chunk)) {
            ci.cancel();
        }
    }
}
