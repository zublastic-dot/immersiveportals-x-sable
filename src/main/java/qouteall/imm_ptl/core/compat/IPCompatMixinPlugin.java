package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.EuphoriaCompatibility;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalUniformCompatibility;
import qouteall.imm_ptl.core.compat.real_camera.RealCameraCompatibility;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhCompatibility;

import java.util.List;
import java.util.Set;

public class IPCompatMixinPlugin implements IMixinConfigPlugin {
    private boolean dhVersionReported;

    @Override
    public void onLoad(String mixinPackage) {
    
    }
    
    @Override
    public String getRefMapperConfig() {
        return null;
    }
    
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {


        LoadingModList modList = LoadingModList.get();
        if (mixinClassName.contains(".dh.")) {
            String dhVersion = version(modList, "distanthorizons");
            boolean supported = DhCompatibility.supports(dhVersion);
            if (dhVersion != null && !dhVersionReported) {
                dhVersionReported = true;
                if (supported) {
                    LogUtils.getLogger().info("IP/Sable DH: enabling portal compatibility for Distant Horizons {}", dhVersion);
                } else {
                    LogUtils.getLogger().warn("IP/Sable DH: portal compatibility disabled for unverified Distant Horizons {}"
                        + " (supported: 3.3.2 and 3.3.3). Portal LODs may be missing or incorrect.", dhVersion);
                }
            }
            return supported;
        }
        if (mixinClassName.contains("RealCamera")) {
            return RealCameraCompatibility.supports(version(modList, "realcamera"));
        }
        if (mixinClassName.contains("Euphoria")) {
            return EuphoriaCompatibility.supports(
                version(modList, "euphoria_patcher"), version(modList, "iris")
            );
        }
        if (mixinClassName.endsWith(".MixinIrisPortalEyeBrightness") || mixinClassName.contains(".MixinIrisSharedSun")
            || mixinClassName.endsWith(".MixinIrisWorldRender")) {
            return IrisPortalUniformCompatibility.supports(version(modList, "iris"));
        }
        if (mixinClassName.contains("IrisSodium")) {
            boolean sodiumLoaded = modList.getModFileById("embeddium") != null || modList.getModFileById("sodium") != null;
            boolean irisLoaded = modList.getModFileById("iris") != null;
            return sodiumLoaded && irisLoaded;
        }
        
        if (mixinClassName.contains("Iris")) {
            boolean irisLoaded = modList.getModFileById("iris") != null;
            return irisLoaded;
        }
        
        if (mixinClassName.contains("Sodium")) {
            boolean sodiumLoaded = modList.getModFileById("embeddium") != null || modList.getModFileById("sodium") != null;
            return sodiumLoaded;
        }
        
        if (mixinClassName.contains("Flywheel")) {
            boolean flywheelLoaded =  modList.getModFileById("flywheel") != null;
            return flywheelLoaded;
        }
        
        if (mixinClassName.contains("CardinalComp")) {
            boolean cardinalCompLoaded = modList.getModFileById("cardinal-components-base") != null;
            return cardinalCompLoaded;
        }
        
        return false;
    }

    private static String version(LoadingModList modList, String modId) {
        var file = modList.getModFileById(modId);
        if (file == null) return null;
        return file.getMods().stream().filter(mod -> mod.getModId().equals(modId))
            .map(mod -> mod.getVersion().toString()).findFirst().orElse(null);
    }
    
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    
    }
    
    @Override
    public List<String> getMixins() {
        return null;
    }
    
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
    
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    
    }
}
