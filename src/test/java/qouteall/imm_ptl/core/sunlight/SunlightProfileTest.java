package qouteall.imm_ptl.core.sunlight;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SunlightProfileTest {
    @Test void disabledByDefaultAndRejectsMalformedProfiles() {
        assertFalse(SunlightProfile.disabled().enabled());
        for (double rotation : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -181, 181})
            assertThrows(IllegalArgumentException.class, () -> new SunlightProfile(true, rotation, SunlightProfile.Clock.SUN_ANGLE));
        assertThrows(NullPointerException.class, () -> new SunlightProfile(true, 0, null));
        assertThrows(IllegalArgumentException.class, () -> SunlightServer.imported(true, 0, "evil"));
        assertThrows(IllegalArgumentException.class, () -> SunlightServer.imported(true, 0, null));
    }
    @Test void rawSunAndMoonAreNeverConfused() {
        for (var clock : SunlightProfile.Clock.values()) {
            var p = new SunlightProfile(true, 20, clock);
            var day = p.sample(6000);
            var night = p.sample(18000);
            assertTrue(day.solarActive());
            assertTrue(day.solarDirection().y > .9);
            assertEquals(day.solarDirection(), day.shadowDirection());
            assertFalse(night.solarActive());
            assertTrue(night.solarDirection().y < -.9);
            assertTrue(night.shadowDirection().y > .9, "the moon may light the shader but cannot burn a mob");
        }
    }
    @Test void trajectoryHasDailyPeriodAndLargeWorldTimesKeepPrecision() {
        var p = new SunlightProfile(true, -37.5, SunlightProfile.Clock.SUN_ANGLE);
        assertEquals(p.sample(9000), p.sample(24000L * 1_000_000_000L + 9000));
        assertEquals(p.sample(23999), p.sample(-1));
        assertEquals(0, SunlightProfile.vanillaSkyAngle(6000, 0), 1e-14);
        assertEquals(.5, SunlightProfile.vanillaSkyAngle(18000, 0), 1e-14);
        assertThrows(IllegalArgumentException.class, () -> SunlightProfile.vanillaSkyAngle(0, Double.NaN));
    }
    @Test void rotationChangesThePathWithoutChangingClockOrSolarIdentity() {
        var a = new SunlightProfile(true, 0, SunlightProfile.Clock.SUN_ANGLE).sample(9000);
        var b = new SunlightProfile(true, 40, SunlightProfile.Clock.SUN_ANGLE).sample(9000);
        assertEquals(a.timeAngle(), b.timeAngle());
        assertEquals(a.solarDirection().x, b.solarDirection().x, 1e-12);
        assertNotEquals(a.solarDirection().z, b.solarDirection().z);
        assertEquals(1, b.solarDirection().length(), 1e-12);
    }
    @Test void noShadowClockRemainsARealProfileChoice() {
        var a = new SunlightProfile(true, 20, SunlightProfile.Clock.SUN_ANGLE).sample(9000);
        var b = new SunlightProfile(true, 20, SunlightProfile.Clock.WORLD_TIME).sample(9000);
        assertEquals(.375, b.timeAngle());
        assertNotEquals(a.timeAngle(), b.timeAngle());
    }
    @Test void savedProfileRoundTripsAndOnlyChangesAdvanceRevision() {
        var data = new SunlightSavedData();
        assertFalse(data.isDirty());
        assertFalse(data.update(SunlightProfile.disabled()));
        assertEquals(1, data.revision());
        var profile = new SunlightProfile(true, -37.5, SunlightProfile.Clock.WORLD_TIME);
        assertTrue(data.update(profile));
        assertTrue(data.isDirty());
        var tag = data.save(new CompoundTag(), null);
        var restored = SunlightSavedData.load(tag, null);
        assertEquals(profile, restored.profile());
        assertEquals(2, restored.revision());
        assertFalse(restored.isDirty());
        assertFalse(restored.update(profile));
        assertTrue(restored.update(profile.withEnabled(false)));
        assertEquals(3, restored.revision());
        assertFalse(restored.profile().enabled());
    }
    @Test void corruptSavedDataFailsDisabledInsteadOfSilentlyEnablingDefaults() {
        var valid = new SunlightSavedData();
        valid.update(new SunlightProfile(true, 20, SunlightProfile.Clock.SUN_ANGLE));
        CompoundTag missing = valid.save(new CompoundTag(), null);
        missing.remove("clock");
        assertFalse(SunlightSavedData.load(missing, null).profile().enabled());
        CompoundTag nan = valid.save(new CompoundTag(), null);
        nan.putDouble("rotation", Double.NaN);
        assertFalse(SunlightSavedData.load(nan, null).profile().enabled());
        CompoundTag future = valid.save(new CompoundTag(), null);
        future.putInt("schema", 900);
        assertFalse(SunlightSavedData.load(future, null).profile().enabled());
    }
}
