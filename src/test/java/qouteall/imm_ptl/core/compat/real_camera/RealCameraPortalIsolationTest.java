package qouteall.imm_ptl.core.compat.real_camera;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.mixin.real_camera.MixinRealCameraCore;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class RealCameraPortalIsolationTest {
    private static CallbackInfoReturnable<Boolean> activity(boolean original) throws Exception {
        var ci = new CallbackInfoReturnable<Boolean>("isActive", true, original);
        invoke("ip_keepPortalCamera", CallbackInfoReturnable.class, ci);
        return ci;
    }

    private static CallbackInfo initialization() throws Exception {
        var ci = new CallbackInfo("initialize", true);
        invoke("ip_preserveMainCameraState", CallbackInfo.class, ci);
        return ci;
    }

    private static void invoke(String name, Class<?> type, Object argument) throws Exception {
        Method hook = MixinRealCameraCore.class.getDeclaredMethod(name, type);
        hook.setAccessible(true);
        hook.invoke(null, argument);
    }

    @Test void absentOrUnverifiedReleasesDoNotLoadTheOptionalMixin() {
        assertTrue(RealCameraCompatibility.supports("0.7.8-beta"));
        assertFalse(RealCameraCompatibility.supports(null));
        assertFalse(RealCameraCompatibility.supports("0.7.7-beta"));
        assertFalse(RealCameraCompatibility.supports("0.8.0"));
    }

    @Test void mainViewPreservesBothEnabledAndDisabledFeatureStates() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            var ci = activity(enabled);
            assertEquals(enabled, ci.getReturnValue());
            assertFalse(ci.isCancelled());
        }
        assertFalse(initialization().isCancelled());
    }

    @Test void recursiveWorldViewsCannotReapplyPlayerCameraOrOverwriteItsFlags() throws Exception {
        // Only stack occupancy matters to these hooks; no live world/GPU is needed.
        WorldRenderInfo.pushRenderInfo(null);
        try {
            assertTrue(initialization().isCancelled());
            for (boolean enabled : new boolean[]{true, false}) {
                var ci = activity(enabled);
                assertFalse(ci.getReturnValue());
                assertTrue(ci.isCancelled());
            }
            WorldRenderInfo.pushRenderInfo(null);
            try {
                assertFalse(activity(true).getReturnValue());
                assertTrue(initialization().isCancelled());
            } finally {
                WorldRenderInfo.popRenderInfo();
            }
            assertFalse(activity(true).getReturnValue());
        } finally {
            WorldRenderInfo.popRenderInfo();
        }
        assertTrue(activity(true).getReturnValue());
        assertFalse(initialization().isCancelled());
    }

    @Test void noSeparateSuppressionFlagCanRemainStuckAfterUnwinding() throws Exception {
        assertThrows(IllegalStateException.class, () -> {
            WorldRenderInfo.pushRenderInfo(null);
            try {
                assertFalse(activity(true).getReturnValue());
                throw new IllegalStateException("render aborted");
            } finally {
                WorldRenderInfo.popRenderInfo();
            }
        });
        assertTrue(activity(true).getReturnValue());
        assertFalse(activity(false).getReturnValue());
        assertFalse(initialization().isCancelled());
    }
}
