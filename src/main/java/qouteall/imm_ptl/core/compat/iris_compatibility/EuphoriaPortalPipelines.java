package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import qouteall.imm_ptl.core.compat.mixin.iris.IEIrisEuphoriaShaderPack;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Euphoria 1.10.5 assumes a single live dimension. A portal temporarily changes
 * Minecraft.level, so its global dimension refresh destroys a pipeline that is
 * still rendering the outer world. Iris already caches GPU pipelines by dimension;
 * keep the corresponding Euphoria pack/defines alive for exactly that lifetime.
 */
public final class EuphoriaPortalPipelines {
    private static final DimensionShaderPacks<NamespacedId, ShaderPack> PACKS = new DimensionShaderPacks<>();
    private static boolean managed = true;
    private static Bindings bindings;

    private EuphoriaPortalPipelines() {}

    public static boolean isManaged() {
        return managed;
    }

    public static void clear() {
        PACKS.clear();
    }

    public static void prepare(NamespacedId dimension) {
        if (!managed || Iris.getCurrentPack().isEmpty()) {
            return;
        }
        try {
            if (bindings == null) {
                bindings = new Bindings();
                Helper.log("Euphoria portal compatibility: retaining shaders per dimension");
            }
            if (!bindings.isEuphoria.getBoolean(null)) {
                return;
            }
            PACKS.select(dimension, EuphoriaPortalPipelines::buildForCurrentView,
                IEIrisEuphoriaShaderPack::ip_setCurrentPack);
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException ex) {
            // Leave upstream recovery active if the optional integration cannot bind.
            // Never silently keep rendering a pack with the wrong dimension defines.
            managed = false;
            PACKS.clear();
            Helper.err("Euphoria portal compatibility unavailable; restoring upstream refresh: " + ex);
        }
    }

    private static ShaderPack buildForCurrentView() {
        try {
            // Use Euphoria's own normalization and macro construction, including
            // modded dimensions. swapToDimension itself does not destroy pipelines.
            String dimension = (String) bindings.currentDimension.invoke(null);
            Object result = bindings.swap.invoke(null, dimension);
            if (result instanceof Enum<?> status && status.name().equals("DONE")) {
                return Iris.getCurrentPack().orElseThrow(
                    () -> new IllegalStateException("Euphoria selected an absent shader pack")
                );
            }
            throw new IllegalStateException("Euphoria dimension swap returned " + result);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not select Euphoria dimension shaders", ex);
        }
    }

    private static final class Bindings {
        final Field isEuphoria;
        final Method currentDimension;
        final Method swap;

        Bindings() throws ReflectiveOperationException {
            String root = "com.euphoriapatches.euphoria_patcher.";
            isEuphoria = Class.forName(root + "integration.DefineHelper")
                .getField("currentShaderpackIsEuphoria");
            currentDimension = Class.forName(root + "util.mod.ModLoaderSpecifics")
                .getMethod("getCurrentDimensionStatic");
            swap = Class.forName(root + "integration.iris.EuphoriaShaderPackCache")
                .getMethod("swapToDimension", String.class);
        }
    }
}
