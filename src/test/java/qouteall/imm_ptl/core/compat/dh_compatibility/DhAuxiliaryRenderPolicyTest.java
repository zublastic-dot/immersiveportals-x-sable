package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the real DH wrappers while replacing only their external render state. */
class DhAuxiliaryRenderPolicyTest {
    public static class Refresh {
        static boolean active;
        public static boolean isRendering() { return active; }
    }
    public static class Portal {
        static boolean active;
        public static boolean isRendering() { return active; }
    }
    public static class Iris {
        public static final IrisInvoker invoker = new IrisInvoker();
        static boolean shadow;
    }
    public static class IrisInvoker {
        public boolean isRenderingShadowMap() { return Iris.shadow; }
    }
    public static class Hooks {
        static int maintenance, scopes;
        public static void maintain() { maintenance++; }
        public static Pass begin() { scopes++; return new Pass(); }
    }
    public static class Pass implements AutoCloseable {
        public void close() {}
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhAuxiliaryRenderPolicyTest.class.getClassLoader()); }
        Class<?> define(byte[] code) { return defineClass(null, code, 0, code.length); }
    }

    private Object mixin;
    private final Map<String, Method> methods = new HashMap<>();
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }
    private ClassNode read(String owner) throws Exception {
        try (var stream = getClass().getResourceAsStream('/' + owner + ".class")) {
            assertNotNull(stream, owner);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    @BeforeEach void loadProductionWrappers() throws Exception {
        var node = read("qouteall/imm_ptl/core/compat/mixin/dh/MixinDhClientApi");
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
            "qouteall/imm_ptl/core/lighting/PortalSourceRefreshPolicy", name(Refresh.class),
            "qouteall/imm_ptl/core/render/context_management/PortalRendering", name(Portal.class),
            "qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface", name(Iris.class),
            "qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface$Invoker", name(IrisInvoker.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalTaa", name(Hooks.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering", name(Hooks.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering$Pass", name(Pass.class)
        ))));
        var type = new Loader().define(writer.toByteArray());
        mixin = type.getConstructor().newInstance();
        for (var method : type.getDeclaredMethods()) {
            method.setAccessible(true);
            methods.put(method.getName(), method);
        }
        Refresh.active = Portal.active = Iris.shadow = false;
        Hooks.maintenance = Hooks.scopes = 0;
    }

    private Object invoke(String method, Object... args) {
        try { return methods.get(method).invoke(mixin, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test void discardedColorSkipsOpaqueTransparentFadeAndHistory() {
        Refresh.active = true;
        Operation<Void> forbidden = args -> { fail("Discarded color must not reach DH"); return null; };
        invoke("ip_renderScope", false, forbidden);
        invoke("ip_renderScope", true, forbidden);
        invoke("ip_fadeScope", forbidden);
        invoke("ip_keepMainZoom", null, null, forbidden);
        assertEquals(0, Hooks.maintenance);
        assertEquals(0, Hooks.scopes);
    }

    @Test void nativeSourceShadowPassRetainsItsArgumentsAndFailureWithoutMainHistory() {
        Refresh.active = Iris.shadow = true;
        int[] calls = {0};
        for (boolean deferred : new boolean[]{false, true}) {
            invoke("ip_renderScope", deferred, (Operation<Void>) args -> {
                assertArrayEquals(new Object[]{deferred}, args);
                calls[0]++;
                return null;
            });
        }
        invoke("ip_fadeScope", (Operation<Void>) args -> { calls[0]++; return null; });
        var failure = new IllegalStateException("native shadow failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
            invoke("ip_renderScope", false, (Operation<Void>) args -> { throw failure; })));
        assertEquals(3, calls[0]);
        assertEquals(0, Hooks.maintenance);
        assertEquals(0, Hooks.scopes);
    }

    @Test void auxiliaryShadowCannotEnterTheMainCameraSpeedOrZoomHistory() {
        Refresh.active = Iris.shadow = true;
        assertEquals(true, invoke("ip_keepMainCameraSpeed", null, (Operation<Boolean>) args -> {
            fail("Auxiliary source position must not become a player-speed sample"); return false;
        }));
        invoke("ip_keepMainZoom", null, null, (Operation<Void>) args -> { fail("Auxiliary zoom"); return null; });
        Refresh.active = false;
        for (boolean nativePortal : new boolean[]{false, true}) {
            assertEquals(nativePortal, invoke("ip_keepMainCameraSpeed", null,
                (Operation<Boolean>) args -> nativePortal));
        }
        int[] zooms = {0};
        invoke("ip_keepMainZoom", null, null, (Operation<Void>) args -> { zooms[0]++; return null; });
        Portal.active = true;
        invoke("ip_keepMainZoom", null, null, (Operation<Void>) args -> { fail("Portal zoom"); return null; });
        assertEquals(1, zooms[0]);
    }

    @Test void ordinaryMainAndPortalRenderingKeepTheirExistingPaths() {
        for (boolean portal : new boolean[]{false, true}) {
            Portal.active = portal;
            int[] calls = {0};
            invoke("ip_renderScope", false, (Operation<Void>) args -> { calls[0]++; return null; });
            invoke("ip_renderScope", true, (Operation<Void>) args -> { calls[0]++; return null; });
            invoke("ip_fadeScope", (Operation<Void>) args -> { calls[0]++; return null; });
            assertEquals(3, calls[0]);
        }
        assertEquals(4, Hooks.maintenance);
        assertEquals(3, Hooks.scopes);
    }

    @Test void actualDhSpeedExclusionPrecedesShadowCheckAndSkipsTheEntireSample() throws Exception {
        var node = read("com/seibel/distanthorizons/core/api/internal/ClientApi");
        var method = node.methods.stream().filter(m -> m.name.equals("renderLodLayer") && m.desc.equals("(Z)V"))
            .findFirst().orElseThrow();
        int portalCalls = 0, portalIndex = -1, addIndex = -1, shadowIndex = -1, skipTarget = -1;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            int index = method.instructions.indexOf(call);
            if (call.owner.endsWith("/IImmersivePortalsAccessor") && call.name.equals("isRenderingPortal")) {
                portalCalls++; portalIndex = index;
                var next = call.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                assertInstanceOf(JumpInsnNode.class, next);
                var branch = (JumpInsnNode) next;
                assertEquals(Opcodes.IFNE, branch.getOpcode(), "True must exclude speed sampling");
                skipTarget = method.instructions.indexOf(branch.label);
            }
            if (call.owner.endsWith("/RollingAverage") && call.name.equals("add")) addIndex = index;
            if (call.owner.endsWith("/IIrisAccessor") && call.name.equals("isRenderingShadowPass")) shadowIndex = index;
        }
        assertEquals(1, portalCalls, "The guard must affect only the inspected speed callsite");
        assertTrue(portalIndex >= 0 && portalIndex < addIndex && addIndex < skipTarget
            && skipTarget < shadowIndex, "Native shadow rendering alone does not protect player-speed history");
    }
}
