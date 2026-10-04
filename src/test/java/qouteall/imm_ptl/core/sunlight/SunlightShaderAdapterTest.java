package qouteall.imm_ptl.core.sunlight;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.shadows.ShadowMatrices;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

class SunlightShaderAdapterTest {
    private static final String PACK = SunlightShaderAdapter.PACK;
    private static final String PROGRAM = """
        #version 330
        #define OVERWORLD
        #define SHADOW_QUALITY -1
        #define SUN_ANGLE 0
        // Thanks to SpacEagle17 and isuewo for the sun angle handling
        #if SUN_ANGLE == 0
        const float sunPathRotation = 0.0;
        #define PERPENDICULAR_TWEAKS
        #endif
        #if SHADOW_QUALITY >= 2
        const int shadowMapResolution=4096;
        #endif
        #if SHADOW_QUALITY == -1
        float timeAngle = worldTimeSmooth / 24000.0;
        #else
        float tAmin = fract(sunAngle - 0.033333333);
        float timeAngle = tAmin;
        #endif
        """;

    @Test void disabledUnknownAndEndLeaveSourceUntouched() {
        var enabled = new SunlightProfile(true, 27.5, SunlightProfile.Clock.SUN_ANGLE);
        assertSame(PROGRAM, SunlightShaderAdapter.patch(PACK, PROGRAM, SunlightProfile.disabled()));
        assertSame(PROGRAM, SunlightShaderAdapter.patch("another-pack", PROGRAM, enabled));
        String end = PROGRAM.replace("#define OVERWORLD", "#define END");
        assertSame(end, SunlightShaderAdapter.patch(PACK, end, enabled));
    }

    @Test void overrideChangesClockAndContinuousRotationWithoutChangingShadowQuality() {
        String output = SunlightShaderAdapter.patch(PACK, PROGRAM,
            new SunlightProfile(true, 27.5, SunlightProfile.Clock.SUN_ANGLE));
        assertTrue(output.contains("#define SHADOW_QUALITY -1"));
        assertTrue(output.contains("const float sunPathRotation = 27.5;"));
        assertTrue(output.contains("#define SUN_ANGLE 1"));
        assertFalse(output.contains("#define PERPENDICULAR_TWEAKS"));
        assertFalse(output.contains("worldTimeSmooth / 24000.0"));
        assertTrue(output.contains("float tAmin = fract(sunAngle"));
        assertSame(output, SunlightShaderAdapter.patch(PACK, output,
            new SunlightProfile(true, 27.5, SunlightProfile.Clock.SUN_ANGLE)));
    }

    @Test void sharedWorldTimeUsesSourceScopedIntegerClockAndRejectsChangedAnchors() {
        var p = new SunlightProfile(true, 0, SunlightProfile.Clock.WORLD_TIME);
        String output = SunlightShaderAdapter.patch(PACK, PROGRAM, p);
        assertTrue(output.contains("float timeAngle = float(worldTime) / 24000.0;"));
        assertTrue(output.contains("#define PERPENDICULAR_TWEAKS"));
        assertThrows(IllegalStateException.class, () -> SunlightShaderAdapter.patch(PACK,
            PROGRAM.replace("worldTimeSmooth / 24000.0", "changedClock / 24000.0"), p));
    }

    @Test void ownerObservationDoesNotDependOnSharedProfileAndRequiresUnambiguousNativeCode() {
        String nativeCode = "const float sunPathRotation = -40.0; float timeAngle = worldTimeSmooth / 24000.0;";
        var p = SunlightShaderAdapter.observe(PACK, nativeCode).orElseThrow();
        assertEquals(-40, p.pathRotationDegrees());
        assertEquals(SunlightProfile.Clock.WORLD_TIME, p.clock());
        assertTrue(SunlightShaderAdapter.observe(PACK, nativeCode + "const float sunPathRotation=20.0;").isEmpty());
        assertTrue(SunlightShaderAdapter.observe("unknown", nativeCode).isEmpty());
    }

    @Test void connectionRevisionsCannotLeakAcrossDisconnectOrOverrideNewerPolicy() {
        var state = new SunlightClient.Session();
        Object first = new Object(), second = new Object();
        var enabled = new SunlightProfile(true, -40, SunlightProfile.Clock.WORLD_TIME);
        assertFalse(state.accept(null, 0, enabled));
        assertTrue(state.accept(first, 9, enabled));
        assertFalse(state.accept(first, 8, SunlightProfile.disabled()));
        assertEquals(enabled, state.profile);
        assertFalse(state.accept(first, 9, SunlightProfile.disabled()));
        state.clear();
        assertFalse(state.profile.enabled());
        assertEquals(-1, state.revision);
        assertTrue(state.accept(second, 0, enabled));
        assertTrue(state.accept(second, 1, SunlightProfile.disabled()));
    }

    @Test void respawnCleanupKeepsPolicyButTrueDisconnectAndNewServerClearIt() {
        var state = new SunlightClient.Session();
        Object server = new Object();
        var enabled = new SunlightProfile(true, 35, SunlightProfile.Clock.SUN_ANGLE);
        assertTrue(state.accept(server, 10, enabled));
        assertFalse(state.clearIfConnectionChanged(server), "same-connection world replacement is not logout");
        assertEquals(enabled, state.profile);
        assertEquals(10, state.revision);
        assertTrue(state.clearIfConnectionChanged(null));
        assertEquals(SunlightProfile.disabled(), state.profile);
        assertEquals(-1, state.revision);
        assertTrue(state.accept(server, 10, enabled));
        assertTrue(state.clearIfConnectionChanged(new Object()));
        assertEquals(SunlightProfile.disabled(), state.profile);
        assertTrue(state.accept(server, 10, enabled));
        assertTrue(state.accept(new Object(), 0, SunlightProfile.disabled()),
            "new server's disabled profile must request native shader restoration even before the cleanup tick");
    }

    @Test void actualIrisShadowMatrixLooksAlongCanonicalLightForBothClocksAndRotations() {
        for (var clock : SunlightProfile.Clock.values()) for (double rotation : new double[]{-40,0,27.5,90,-160}) {
            var p = new SunlightProfile(true, rotation, clock);
            for (long tick : new long[]{0,1000,6000,8000,9000,9500,12000,18000,23000}) {
                var d = p.sample(tick).shadowDirection();
                var stack = new PoseStack();
                ShadowMatrices.createBaselineModelViewMatrix(stack, SunlightClient.shadowAngle(p, d), (float)rotation, -160, 160);
                Vector3f ray = new Matrix4f(stack.last().pose()).invert().transformDirection(new Vector3f(0,0,1)).normalize();
                assertEquals(1, ray.x*d.x + ray.y*d.y + ray.z*d.z, 0.00001,
                    clock + " rotation=" + rotation + " time=" + tick + " actual=" + ray + " expected=" + d);
            }
        }
    }

    @Test void verifiedIrisHasEveryNativeHookWithItsExpectedDescriptorAndStaticShape() throws Exception {
        for (String name : new String[]{"net/irisshaders/iris/shadows/ShadowRenderer", "net/irisshaders/iris/uniforms/CelestialUniforms"}) {
            var node = new ClassNode();
            try (var stream = getClass().getResourceAsStream("/" + name + ".class")) {
                assertNotNull(stream); new ClassReader(stream).accept(node, 0);
            }
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("getShadowAngle") && m.desc.equals("()F")
                && (m.access & Opcodes.ACC_STATIC) != 0));
            if (name.endsWith("CelestialUniforms")) {
                for (String method : new String[]{"getCelestialPosition", "getCelestialPositionInWorldSpace"})
                    assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(method) && m.desc.equals("(F)Lorg/joml/Vector4f;")
                        && (m.access & Opcodes.ACC_STATIC) == 0));
                assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("isDay") && m.desc.equals("()Z")
                    && (m.access & Opcodes.ACC_STATIC) != 0));
            }
        }
        assertTrue(qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalUniformCompatibility.supports("1.8.14-beta.1+mc1.21.1"));
        assertFalse(qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalUniformCompatibility.supports("1.8.13"));
    }
}
