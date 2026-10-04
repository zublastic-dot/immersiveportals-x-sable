package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhRenderTraceProbe;

@Pseudo
@Mixin(value = GlDhTerrainShaderProgram_neoforge.class, remap = false)
public class MixinDhRenderTraceTerrain {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/coreapi/DependencyInjection/ApiEventInjector;fireAllEvents(Ljava/lang/Class;Ljava/lang/Object;)Z", ordinal = 0, shift = At.Shift.AFTER))
    private void ip_tracePass(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler, CallbackInfo ci) {
        DhRenderTraceProbe.sample(params, "selectedPass");
    }
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/coreapi/DependencyInjection/ApiEventInjector;fireAllEvents(Ljava/lang/Class;Ljava/lang/Object;)Z", ordinal = 1, shift = At.Shift.AFTER))
    private void ip_traceBuffer(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler, CallbackInfo ci) {
        DhRenderTraceProbe.sample(params, "selectedBuffer");
    }
}
