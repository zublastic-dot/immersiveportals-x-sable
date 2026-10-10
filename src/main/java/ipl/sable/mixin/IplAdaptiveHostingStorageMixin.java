package ipl.sable.mixin;

import ipl.sable.dim.IplAdaptiveStorageBootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class IplAdaptiveHostingStorageMixin {
    // NeoForge and Lithostitched finish their startup modifiers before loadLevel enters createLevels.
    // This boundary precedes the first ServerLevel, including its chunk storage and light engine.
    @Inject(method = "createLevels", at = @At("HEAD"), require = 1)
    private void iplsable$selectStorageProfile(ChunkProgressListener listener, CallbackInfo ci) {
        IplAdaptiveStorageBootstrap.initialize((MinecraftServer) (Object) this);
    }
}
