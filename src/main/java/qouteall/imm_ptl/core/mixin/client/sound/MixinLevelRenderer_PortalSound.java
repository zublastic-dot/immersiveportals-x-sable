package qouteall.imm_ptl.core.mixin.client.sound;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.JukeboxSong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer_PortalSound {
    @Shadow private ClientLevel level;

    @WrapMethod(method = "playJukeboxSong")
    private void portal$jukeboxOwner(Holder<JukeboxSong> song, BlockPos position, Operation<Void> original) {
        try (var ignored = PortalSoundManager.producer(level)) { original.call(song, position); }
    }
}
