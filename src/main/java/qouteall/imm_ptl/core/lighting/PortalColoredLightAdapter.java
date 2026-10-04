package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/** Optional public-API bridge; every query uses the supplied source level, never Colorful's singleton engine. */
final class PortalColoredLightAdapter {
    private static Method engineEnabled;
    private static boolean gateChecked, unavailable;
    private static Api api;
    private static String reason = "Colorful not checked";

    static boolean available() {
        if (unavailable) return false;
        try {
            if (!gateChecked) {
                gateChecked = true;
                engineEnabled = Class.forName("dev.colorfullighting.compat.CompatGates").getMethod("engineEnabled");
            }
            if (!(boolean) engineEnabled.invoke(null)) { reason = "Colorful engine disabled"; return false; }
            if (api == null) api = new Api();
            reason = "Colorful native config";
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            unavailable = true; reason = "Colorful API unavailable: " + failure.getClass().getSimpleName(); return false;
        }
    }
    static String reason() { return reason; }
    static PortalColoredLightField.Reader reader(ClientLevel source) {
        if (!available()) return null;
        try {
            Api current = api;
            Object level = current.level.newInstance(source, null);
            // In pinned 2.5.1, filters depend only on state/config; wrappers hold only immutable state.
            // Scope both to this snapshot so config reloads are observed by its next refresh.
            var states = new IdentityHashMap<BlockState, StateInput>();
            return position -> {
                if (unavailable) return PortalColoredLightField.Cell.UNKNOWN;
                BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
                if (source.isOutsideBuildHeight(pos) || !source.hasChunkAt(pos)) return PortalColoredLightField.Cell.UNKNOWN;
                try {
                    BlockState state = source.getBlockState(pos);
                    StateInput input = states.get(state);
                    if (input == null) {
                        Object wrapped = current.state.newInstance(state);
                        input = new StateInput(wrapped, current.color(current.filter.invoke(null, level, pos, wrapped)));
                        if (states.size() < 256) states.put(state, input);
                    }
                    // Call emission even for vanilla-dark states: user overrides can give them native RGB.
                    // Neither brightness nor opacity may be cached by state: both can depend on position.
                    var emission = current.color(current.emission.invoke(null, level, pos, input.wrapper));
                    return new PortalColoredLightField.Cell(emission, input.filter, state.getLightBlock(source, pos));
                } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                    unavailable = true; reason = "Colorful read failed: " + failure.getClass().getSimpleName();
                    return PortalColoredLightField.Cell.UNKNOWN;
                }
            };
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            unavailable = true; reason = "Colorful source wrapper failed: " + failure.getClass().getSimpleName(); return null;
        }
    }
    private record StateInput(Object wrapper, PortalColoredLightField.Rgb filter) {}
    private static final class Api {
        final Constructor<?> level, state;
        final Method emission, filter;
        final Field red, green, blue;
        Api() throws ReflectiveOperationException {
            Class<?> levelType = Class.forName("me.erykczy.colorfullighting.common.accessors.LevelAccessor");
            Class<?> stateType = Class.forName("me.erykczy.colorfullighting.common.accessors.BlockStateAccessor");
            Class<?> colorType = Class.forName("me.erykczy.colorfullighting.common.util.ColorRGB4");
            Class<?> config = Class.forName("me.erykczy.colorfullighting.common.Config");
            level = Class.forName("me.erykczy.colorfullighting.accessors.LevelWrapper").getConstructor(ClientLevel.class, LevelRenderer.class);
            state = Class.forName("me.erykczy.colorfullighting.accessors.BlockStateWrapper").getConstructor(BlockState.class);
            emission = config.getMethod("getColorEmission", levelType, BlockPos.class, stateType);
            filter = config.getMethod("getColoredLightTransmittance", levelType, BlockPos.class, stateType);
            red = colorType.getField("red4"); green = colorType.getField("green4"); blue = colorType.getField("blue4");
        }
        PortalColoredLightField.Rgb color(Object color) throws IllegalAccessException {
            return new PortalColoredLightField.Rgb(red.getInt(color), green.getInt(color), blue.getInt(color));
        }
    }
    private PortalColoredLightAdapter() {}
}
