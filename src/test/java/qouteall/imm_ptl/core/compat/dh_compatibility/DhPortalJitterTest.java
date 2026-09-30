package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the compiled production wrapper; substitute only the game/GL boundaries. */
public class DhPortalJitterTest {
    private Object mixin;
    private Field phase;
    private Method upload;
    private final DhApiRenderParam params = new DhApiRenderParam();

    public static class Hooks {
        public static boolean portal;
        public static int jitter;
        public static int mainPhase = -1;
        public static int mainPhaseBeforeIncrement() { return mainPhase; }
        public static int recordedPhase;
        public static void recordMainSample(int phase) { recordedPhase = phase; }
        public static int jitterPhase() { return jitter; }
        public static final List<Float> uniforms = new ArrayList<>();
        public static boolean isRendering() { return portal; }
        public static void glUniform1f(int location, float value) {
            assertEquals(17, location);
            uniforms.add(value);
        }
    }

    private static class WrapperLoader extends ClassLoader {
        WrapperLoader() { super(DhPortalJitterTest.class.getClassLoader()); }
        Class<?> define(byte[] code) { return defineClass(null, code, 0, code.length); }
    }

    @BeforeEach void loadProductionWrapper() throws Exception {
        String path = "/qouteall/imm_ptl/core/compat/mixin/dh/MixinDhTerrainAntiAliasing.class";
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream);
            new ClassReader(stream).accept(node, 0);
        }
        int replaced = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && (call.owner.equals("qouteall/imm_ptl/core/render/context_management/PortalRendering")
                        || call.owner.equals("org/lwjgl/opengl/GL33")
                        || call.owner.equals("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalTaa"))) {
                    call.owner = Hooks.class.getName().replace('.', '/');
                    replaced++;
                }
            }
        }
        assertEquals(5, replaced, "Only the live portal flag and GL uniform upload are substituted");
        var writer = new ClassWriter(0);
        node.accept(writer);
        Class<?> type = new WrapperLoader().define(writer.toByteArray());
        mixin = type.getConstructor().newInstance();
        phase = type.getDeclaredField("frameIndexMod8");
        phase.setAccessible(true);
        type.getField("uFrameMod8").setInt(mixin, 17);
        upload = type.getDeclaredMethod("ip_stablePortalSamples", DhApiRenderParam.class, Operation.class);
        upload.setAccessible(true);
        Hooks.portal = true;
        Hooks.mainPhase = -1;
        Hooks.uniforms.clear();
    }

    private Operation<Void> animatedUpload() {
        return arguments -> {
            assertSame(params, arguments[0]);
            try {
                int next = (phase.getInt(mixin) + 1) % 8;
                phase.setInt(mixin, next);
                Hooks.glUniform1f(17, next);
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
            return null;
        };
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7})
    void repeatedPortalViewsUsePrivatePhaseAndDoNotConsumeOuterPhase(int initial) throws Exception {
        phase.setInt(mixin, initial);
        for (int i = 0; i < 16; i++) {
            Hooks.jitter = i & 7;
            upload.invoke(mixin, params, animatedUpload());
            assertEquals((float)(i & 7), Hooks.uniforms.getLast());
            assertEquals(initial, phase.getInt(mixin));
        }
        // Entering the actual dimension keeps DH's original sample sequence.
        Hooks.portal = false;
        upload.invoke(mixin, params, animatedUpload());
        assertEquals((initial + 1) % 8, phase.getInt(mixin));
        assertEquals((float)((initial + 1) % 8), Hooks.uniforms.getLast());
    }

    @Test void dhDisabledAaAndShaderVetoIsPreserved() throws Exception {
        Hooks.jitter = 4;
        phase.setInt(mixin, 6);
        Operation<Void> disabled = arguments -> {
            try { phase.setInt(mixin, -1); } catch (Exception e) { throw new AssertionError(e); }
            return null;
        };
        upload.invoke(mixin, params, disabled);
        assertEquals(-1f, Hooks.uniforms.getLast());
        assertEquals(6, phase.getInt(mixin));
    }

    @Test void normalViewUniformUploadsAreUntouched() throws Exception {
        Hooks.portal = false;
        phase.setInt(mixin, 3);
        upload.invoke(mixin, params, animatedUpload());
        assertEquals(List.of(4.0f), Hooks.uniforms);
        assertEquals(4, Hooks.recordedPhase);
        assertEquals(4, phase.getInt(mixin));
    }

    @Test void crossingContinuesDonatedPhaseOncePerFrameAndStillHonorsDhVeto() throws Exception {
        Hooks.portal = false; Hooks.mainPhase = 7; phase.setInt(mixin, 3);
        upload.invoke(mixin, params, animatedUpload());
        upload.invoke(mixin, params, animatedUpload());
        assertEquals(List.of(0.0f,0.0f), Hooks.uniforms);
        Hooks.mainPhase = -1;
        upload.invoke(mixin, params, animatedUpload());
        assertEquals(1, phase.getInt(mixin));
        Hooks.mainPhase = 4;
        upload.invoke(mixin, params, (Operation<Void>) arguments -> {
            try { phase.setInt(mixin, -1); } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
        assertEquals(-1, phase.getInt(mixin));
    }

    @Test void failingPortalUploadRestoresPhaseWithoutSwallowingFailure() throws Exception {
        phase.setInt(mixin, 6);
        RuntimeException failure = new RuntimeException("uniform upload failed");
        Operation<Void> failing = arguments -> {
            animatedUpload().call(arguments);
            throw failure;
        };
        var thrown = assertThrows(InvocationTargetException.class,
            () -> upload.invoke(mixin, params, failing));
        assertSame(failure, thrown.getCause());
        assertEquals(6, phase.getInt(mixin));
        assertEquals(List.of(7.0f), Hooks.uniforms);
    }
}
