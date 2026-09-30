package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the production fade wrappers with just the live portal/GL scope replaced. */
public class DhPortalFadeScopeTest {
    public static class Hooks {
        static boolean portal;
        static Pass current;
        static int begun;
        public static boolean isRendering() { return portal; }
        public static boolean isSupportedPass() { return current != null && current.valid; }
        public static Pass begin() { begun++; return new Pass(); }
    }
    public static class Pass implements AutoCloseable {
        final Pass parent = Hooks.current;
        boolean valid = true;
        Pass() { Hooks.current = this; }
        public void close() { Hooks.current = parent; }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhPortalFadeScopeTest.class.getClassLoader()); }
        Class<?> define(byte[] code) { return defineClass(null, code, 0, code.length); }
    }
    private Object mixin;
    private Method scope, allowed, draw;
    private static String name(Class<?> c) { return c.getName().replace('.', '/'); }

    @BeforeEach void loadWrappers() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/qouteall/imm_ptl/core/compat/mixin/dh/MixinDhClientApi.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(node, 0);
        }
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
            "qouteall/imm_ptl/core/render/context_management/PortalRendering", name(Hooks.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering", name(Hooks.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering$Pass", name(Pass.class)
        ))));
        Class<?> type = new Loader().define(writer.toByteArray());
        mixin = type.getConstructor().newInstance();
        for (Method m : type.getDeclaredMethods()) {
            m.setAccessible(true);
            if (m.getName().equals("ip_fadeScope")) scope = m;
            if (m.getName().equals("ip_allowScopedFade")) allowed = m;
            if (m.getName().equals("ip_fadeOnlyValidView")) draw = m;
        }
        assertNotNull(scope); assertNotNull(allowed); assertNotNull(draw);
        Hooks.portal = true; Hooks.current = null; Hooks.begun = 0;
    }
    private void fade(Operation<Void> body) {
        try { scope.invoke(mixin, body); }
        catch (InvocationTargetException e) { throw (RuntimeException)e.getCause(); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private boolean portalVeto() {
        try { return (boolean) allowed.invoke(null, null, (Operation<Boolean>) args -> true); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void draw(Operation<Void> operation) {
        try { draw.invoke(mixin, null, null, operation); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test void bothFadeStagesCanRepreparePortalMatricesAndRestoreParentScope() {
        assertTrue(portalVeto(), "Unscoped DH remains protected");
        for (int stage = 0; stage < 2; stage++) fade(args -> {
            Pass outer = Hooks.current;
            assertTrue(Hooks.isSupportedPass());
            assertFalse(portalVeto());
            fade(nested -> { assertNotSame(outer, Hooks.current); return null; });
            assertSame(outer, Hooks.current);
            return null;
        });
        assertEquals(4, Hooks.begun);
        assertNull(Hooks.current);
        assertTrue(portalVeto());
    }

    @Test void invalidRebuiltProjectionDoesNotFadeTowardStaleImages() {
        int[] draws = {0};
        fade(args -> {
            draw(a -> { draws[0]++; return null; });
            Hooks.current.valid = false; // RenderParams preparation rejected the clip plane
            draw(a -> { fail("Invalid view must not reach the fade renderer"); return null; });
            return null;
        });
        assertEquals(1, draws[0]);
    }

    @Test void directViewKeepsTheOriginalPathAndCreatesNoScope() {
        Hooks.portal = false;
        int[] draws = {0};
        fade(args -> { draw(a -> { draws[0]++; return null; }); return null; });
        assertEquals(1, draws[0]);
        assertEquals(0, Hooks.begun);
    }

    @Test void failingFadeRestoresParentWithoutSwallowingFailure() {
        var failure = new RuntimeException("fade failed");
        try (var parent = Hooks.begin()) {
            assertSame(failure, assertThrows(RuntimeException.class, () -> fade(args -> { throw failure; })));
            assertSame(parent, Hooks.current);
        }
        assertNull(Hooks.current);
    }
}
