package qouteall.imm_ptl.core.sunlight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.q_misc_util.my_util.DQuaternion;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class SunlightExposureTest {
    private static final World OPEN = p -> Cell.OPEN;
    private static final Vec3 SUN = new Vec3(-1, 1, 0).normalize();
    private static SunlightExposure.Sky sky(World world, Vec3 sun) {
        return new SunlightExposure.Sky(world, 12, new SunlightProfile.Sample(sun, sun, .4, true), 1);
    }
    private static SunlightExposure.Aperture aperture(SunlightExposure.Sky source) {
        return new SunlightExposure.Aperture(new Vec3(0, 4, .5), new Vec3(1, 0, 0),
            new Vec3(0, 1, 0), new Vec3(0, 0, 1), 6, 6, p -> p.add(20, 0, 0), v -> v, source);
    }
    @Test void sideWindowLetsSunReachAnOrdinaryCoveredRoom() {
        Vec3 eye = new Vec3(4.5, .5, .5);
        World room = p -> p.y() == 6 && p.x() > 0 ? Cell.CLOSED : Cell.OPEN;
        assertEquals(1, SunlightExposure.direct(sky(room, SUN), eye, new SunlightExposure.Budget(4096)));
        assertEquals(0, SunlightExposure.direct(sky(room, new Vec3(0, 1, 0)), eye, new SunlightExposure.Budget(4096)));
        World sealed = p -> p.x() == 0 || p.y() == 6 ? Cell.CLOSED : Cell.OPEN;
        assertEquals(0, SunlightExposure.direct(sky(sealed, SUN), eye, new SunlightExposure.Budget(4096)));
    }
    @Test void portalRequiresBothReceivingSegmentAndSourceSkyToBeClear() {
        Vec3 eye = new Vec3(4.5, .5, .5);
        assertEquals(1, SunlightExposure.through(OPEN, eye, aperture(sky(OPEN, SUN)), new SunlightExposure.Budget(4096)));
        assertEquals(0, SunlightExposure.through(p -> p.x() == 2 ? Cell.CLOSED : Cell.OPEN,
            eye, aperture(sky(OPEN, SUN)), new SunlightExposure.Budget(4096)));
        assertEquals(0, SunlightExposure.through(OPEN, eye,
            aperture(sky(p -> p.y() == 8 ? Cell.CLOSED : Cell.OPEN, SUN)), new SunlightExposure.Budget(4096)));
    }
    @Test void apertureEdgesAndBackSideNeverLeak() {
        var a = aperture(sky(OPEN, SUN));
        assertEquals(0, SunlightExposure.through(OPEN, new Vec3(4.5, .5, 3.5), a, new SunlightExposure.Budget(4096)));
        assertEquals(0, SunlightExposure.through(OPEN, new Vec3(-4.5, .5, .5), a, new SunlightExposure.Budget(4096)));
    }
    @Test void rotatingThePortalRotatesIncomingSunWithoutChangingSourceSun() {
        var turn = DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90);
        var old = aperture(sky(OPEN, SUN));
        var rotated = new SunlightExposure.Aperture(turn.rotate(old.center()), turn.rotate(old.normal()),
            turn.rotate(old.u()), turn.rotate(old.v()), old.width(), old.height(),
            p -> old.toSourcePoint().apply(turn.getConjugated().rotate(p)), turn::rotate, old.source());
        assertEquals(1, SunlightExposure.through(OPEN, turn.rotate(new Vec3(4.5, .5, .5)), rotated, new SunlightExposure.Budget(4096)));
    }
    @Test void sourceUnknownNeverLoadsOrInventsClearSky() {
        var reads = new AtomicInteger();
        var budget = new SunlightExposure.Budget(4096);
        var source = sky(p -> { reads.incrementAndGet(); return Cell.UNKNOWN; }, SUN);
        assertEquals(0, SunlightExposure.through(OPEN, new Vec3(4.5, .5, .5), aperture(source), budget));
        assertEquals(1, reads.get());
        assertTrue(budget.unknown());
    }
    @Test void totalQueryBudgetIsSharedAcrossRaysAndStopsCallingReaders() {
        var reads = new AtomicInteger();
        var budget = new SunlightExposure.Budget(3);
        var source = sky(p -> { reads.incrementAndGet(); return Cell.OPEN; }, SUN);
        assertEquals(0, SunlightExposure.direct(source, new Vec3(.5, .5, .5), budget));
        assertEquals(3, reads.get());
        assertEquals(0, SunlightExposure.direct(source, new Vec3(.5, .5, .5), budget));
        assertEquals(3, reads.get());
        assertTrue(budget.exhausted());
    }
    @Test void darkWeatherAndMoonNeverCountAsDirectSun() {
        var night = new SunlightProfile(true, 0, SunlightProfile.Clock.WORLD_TIME).sample(18000);
        assertEquals(0, SunlightExposure.direct(new SunlightExposure.Sky(OPEN, 12, night, 1), Vec3.ZERO, new SunlightExposure.Budget(4096)));
        assertEquals(1, SunlightExposure.weatherStrength(0, 0));
        assertEquals(0, SunlightExposure.weatherStrength(1, 0));
        assertEquals(0, SunlightExposure.weatherStrength(0, 1));
        assertEquals(.25, SunlightExposure.weatherStrength(.5f, .5f));
        assertEquals(0, SunlightExposure.weatherStrength(Float.NaN, 0));
    }
    @Test void vanillaChanceAndEnvironmentalProtectionsArePreserved() {
        assertTrue(SunlightExposure.burnTick(1, .01f, false, false, false));
        assertFalse(SunlightExposure.burnTick(1, .05f, false, false, false));
        assertFalse(SunlightExposure.burnTick(.5, 0, false, false, false));
        assertFalse(SunlightExposure.burnTick(1, 0, true, false, false));
        assertFalse(SunlightExposure.burnTick(1, 0, false, true, false));
        assertFalse(SunlightExposure.burnTick(1, 0, false, false, true));
        assertFalse(SunlightExposure.burnTick(Double.NaN, 0, false, false, false));
    }
    @Test void newObservationSeesClosedOpeningImmediatelyWithoutStaleCache() {
        boolean[] closed = {false};
        World scene = p -> closed[0] && p.x() == 2 ? Cell.CLOSED : Cell.OPEN;
        Vec3 eye = new Vec3(4.5, .5, .5);
        var a = aperture(sky(OPEN, SUN));
        assertEquals(1, SunlightExposure.through(scene, eye, a, new SunlightExposure.Budget(4096)));
        closed[0] = true;
        assertEquals(0, SunlightExposure.through(scene, eye, a, new SunlightExposure.Budget(4096)));
    }
}
