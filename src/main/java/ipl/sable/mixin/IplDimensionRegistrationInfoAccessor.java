package ipl.sable.mixin;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(MappedRegistry.class)
public interface IplDimensionRegistrationInfoAccessor {
    @Accessor("registrationInfos")
    Map<ResourceKey<?>, RegistrationInfo> iplsable$getStorageRegistrationInfos();
}
