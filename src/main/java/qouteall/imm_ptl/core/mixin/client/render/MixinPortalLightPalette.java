package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.lighting.PortalLighting;

@Mixin(LightTexture.class)
public class MixinPortalLightPalette {
    @Shadow @Final private NativeImage lightPixels;
    @Inject(method="updateLightTexture",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"))
    private void ip_capturePalette(float partialTick,CallbackInfo ci) {
        int[] pixels=new int[256];
        for (int y=0;y<16;y++) for (int x=0;x<16;x++) pixels[y*16+x]=lightPixels.getPixelRGBA(x,y);
        PortalLighting.capture(Minecraft.getInstance().level,pixels);
    }
}
