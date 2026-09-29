package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Execute installed DH's decision with the compiled production hook. Only live
 * game/configuration boundaries are substituted; the decision is not reimplemented. */
public class DhPortalFogPolicyTest {
    public enum FogMode { FOG_TERRAIN, FOG_SKY }
    public enum Fluid { NONE, WATER, LAVA, POWDER_SNOW }
    public static class Entity {}
    public static class LivingEntity extends Entity {
        boolean blind;
        public boolean hasEffect(Object effect) { return blind; }
    }
    public static class Camera {
        Entity entity = new LivingEntity();
        Fluid fluid = Fluid.NONE;
        public Entity getEntity() { return entity; }
        public Fluid getFluidInCamera() { return fluid; }
    }
    public static class Entry {
        boolean value;
        Entry(boolean value) { this.value = value; }
        public Object get() { return value; }
    }
    public static class Settings {
        public static Entry quickEnableRendering = new Entry(true);
        public static Entry enableVanillaFog = new Entry(false);
        public static Object BLINDNESS = new Object();
    }
    public interface PortalAccessor { boolean isRenderingPortal(); }
    public interface RenderWrapper { boolean isFogStateSpecial(); }
    public static class Injector {
        public static Injector INSTANCE = new Injector();
        public Object get(Class<?> type) {
            if (type == PortalAccessor.class) return (PortalAccessor) () -> Hooks.portal;
            if (type == RenderWrapper.class) return (RenderWrapper) () -> Hooks.special;
            throw new AssertionError(type);
        }
    }
    public static class Hooks {
        static boolean portal, special;
        public static boolean isRendering() { return portal; }
        public static Operation<Boolean> originalPortalQuery() {
            return arguments -> ((PortalAccessor) arguments[0]).isRenderingPortal();
        }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhPortalFogPolicyTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }
    private static ClassNode read(String name) throws Exception {
        try (var stream = DhPortalFogPolicyTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static Method decision(boolean patched) throws Exception {
        String upstream = "com/seibel/distanthorizons/common/commonMixins/MixinVanillaFogCommon_neoforge";
        String accessor = "com/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor";
        String generated = "qouteall/imm_ptl/core/compat/dh_compatibility/ExtractedDhFogPolicy";
        var node = read(upstream);
        if (patched) {
            var mixin = read("qouteall/imm_ptl/core/compat/mixin/dh/MixinDhVanillaFog");
            var hook = mixin.methods.stream().filter(m -> m.name.equals("ip_useDestinationFogPolicy"))
                .findFirst().orElseThrow();
            var annotation = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/WrapOperation;"))
                .findFirst().orElseThrow();
            assertEquals(java.util.List.of("cancelFog"), annotation.values.get(annotation.values.indexOf("method") + 1));
            var at = (AnnotationNode) ((java.util.List<?>) annotation.values.get(annotation.values.indexOf("at") + 1)).getFirst();
            assertEquals("L" + accessor + ";isRenderingPortal()Z", at.values.get(at.values.indexOf("target") + 1));
            int replaced = 0;
            for (var method : node.methods) {
                for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(accessor)
                        && call.name.equals("isRenderingPortal") && call.desc.equals("()Z")) {
                        method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            name(Hooks.class), "originalPortalQuery",
                            "()Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;", false));
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            upstream, hook.name, hook.desc, false));
                        replaced++;
                    }
                }
            }
            assertEquals(1, replaced, "Patch only DH's portal veto, preserving all other branches");
            node.methods.add(hook);
        }
        var mappings = Map.ofEntries(
            Map.entry(upstream, generated),
            Map.entry("net/minecraft/client/Camera", name(Camera.class)),
            Map.entry("net/minecraft/client/renderer/FogRenderer$FogMode", name(FogMode.class)),
            Map.entry("net/minecraft/world/level/material/FogType", name(Fluid.class)),
            Map.entry("net/minecraft/world/entity/Entity", name(Entity.class)),
            Map.entry("net/minecraft/world/entity/LivingEntity", name(LivingEntity.class)),
            Map.entry("net/minecraft/world/effect/MobEffects", name(Settings.class)),
            Map.entry("net/minecraft/core/Holder", "java/lang/Object"),
            Map.entry("com/seibel/distanthorizons/core/config/Config$Client", name(Settings.class)),
            Map.entry("com/seibel/distanthorizons/core/config/Config$Client$Advanced$Graphics$Fog", name(Settings.class)),
            Map.entry("com/seibel/distanthorizons/core/config/types/ConfigEntry", name(Entry.class)),
            Map.entry("com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", name(Injector.class)),
            Map.entry("com/seibel/distanthorizons/core/dependencyInjection/ModAccessorInjector", name(Injector.class)),
            Map.entry("com/seibel/distanthorizons/coreapi/interfaces/dependencyInjection/IBindable", "java/lang/Object"),
            Map.entry(accessor, name(PortalAccessor.class)),
            Map.entry("com/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper", name(RenderWrapper.class)),
            Map.entry("qouteall/imm_ptl/core/render/context_management/PortalRendering", name(Hooks.class))
        );
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(mappings)));
        return new Loader().define(writer.toByteArray()).getMethod("cancelFog", Camera.class, FogMode.class);
    }

    @Test void installedDhReenablesDistanceFogOnlyInPortalAndHookRemovesThatDifference() throws Exception {
        var original = decision(false);
        var patched = decision(true);
        var camera = new Camera();
        Settings.quickEnableRendering.value = true;
        Settings.enableVanillaFog.value = false;
        Hooks.special = false;
        Hooks.portal = false;
        assertEquals(true, original.invoke(null, camera, FogMode.FOG_TERRAIN));
        Hooks.portal = true;
        assertEquals(false, original.invoke(null, camera, FogMode.FOG_TERRAIN), "Control: vanilla fog returns in a portal");
        assertEquals(true, patched.invoke(null, camera, FogMode.FOG_TERRAIN), "Repair: honor the same DH fog setting");
    }

    @Test void portalMatchesDirectViewWithoutLosingUserFluidBlindnessSkyOrSpecialFog() throws Exception {
        var original = decision(false);
        var patched = decision(true);
        var camera = new Camera();
        for (int flags = 0; flags < 16; flags++) {
            Settings.quickEnableRendering.value = (flags & 1) != 0;
            Settings.enableVanillaFog.value = (flags & 2) != 0;
            Hooks.special = (flags & 4) != 0;
            ((LivingEntity) camera.entity).blind = (flags & 8) != 0;
            for (Fluid fluid : Fluid.values()) for (FogMode mode : FogMode.values()) {
                camera.fluid = fluid;
                Hooks.portal = false;
                Object expected = original.invoke(null, camera, mode);
                assertEquals(expected, patched.invoke(null, camera, mode), "Ordinary view unchanged");
                Hooks.portal = true;
                assertEquals(expected, patched.invoke(null, camera, mode), "Portal retains all ordinary fog safeguards");
            }
        }
    }
}
