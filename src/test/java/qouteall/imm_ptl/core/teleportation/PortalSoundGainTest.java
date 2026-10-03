package qouteall.imm_ptl.core.teleportation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSoundGainTest {
    @Test void initialClosedRouteIsMutedBeforeFirstAuthoredGainAndRestoresComposedValue() {
        var gain = new PortalSoundGain();
        assertEquals(0, gain.setMuted(true));
        assertEquals(0, gain.nativeWrite(.375f));
        assertEquals(.375f, gain.setMuted(false));
    }

    @Test void nativeVolumeChangesWhileClosedAreRetainedWithoutReapplyingModifiers() {
        var gain = new PortalSoundGain();
        assertEquals(.4f, gain.nativeWrite(.4f));
        assertEquals(0, gain.setMuted(true));
        assertEquals(0, gain.nativeWrite(.125f));
        assertEquals(.125f, gain.setMuted(false));
        assertEquals(.125f, gain.setMuted(false));
    }

    @Test void upstreamMuteCannotBeUndoneByOpeningPortal() {
        var gain = new PortalSoundGain();
        gain.nativeWrite(.4f);
        gain.setMuted(true);
        gain.nativeWrite(0);
        assertEquals(0, gain.setMuted(false));
    }

    @Test void healthyRoutesPreserveEveryNativeVolumeWrite() {
        var gain = new PortalSoundGain();
        for (float value : new float[]{0, .01f, .2f, .85f, 1}) assertEquals(value, gain.nativeWrite(value));
        assertFalse(gain.muted());
    }

    @Test void stoppedSourceResetCannotLeakMuteOrOldGainToNextOwner() {
        var gain = new PortalSoundGain();
        gain.nativeWrite(.73f);
        gain.setMuted(true);
        gain.reset();
        assertFalse(gain.muted());
        assertEquals(.17f, gain.nativeWrite(.17f));
    }
}
