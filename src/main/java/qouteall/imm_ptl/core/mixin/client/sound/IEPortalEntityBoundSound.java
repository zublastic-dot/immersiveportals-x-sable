package qouteall.imm_ptl.core.mixin.client.sound;

import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityBoundSoundInstance.class)
public interface IEPortalEntityBoundSound {
    @Accessor("entity") Entity portal$getEmitter();
}
