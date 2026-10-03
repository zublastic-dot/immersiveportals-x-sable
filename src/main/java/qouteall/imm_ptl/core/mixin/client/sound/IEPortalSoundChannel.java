package qouteall.imm_ptl.core.mixin.client.sound;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Channel.class)
public interface IEPortalSoundChannel {
    @Accessor("source") int portal$getSourceId();
}
