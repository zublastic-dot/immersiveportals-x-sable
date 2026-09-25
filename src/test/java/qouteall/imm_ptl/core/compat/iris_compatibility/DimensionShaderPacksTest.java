package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DimensionShaderPacksTest {
    @Test void repeatedNestedPortalViewsBuildEachDimensionOnceAndRestoreOuterPack() {
        var cache = new DimensionShaderPacks<String, String>();
        var builds = new AtomicInteger();
        List<String> active = new ArrayList<>();
        for (int frame = 0; frame < 120; frame++) {
            for (String dim : List.of("overworld", "nether", "custom:moon", "nether", "overworld")) {
                cache.select(dim, () -> { builds.incrementAndGet(); return "macros:" + dim; }, active::add);
                assertEquals("macros:" + dim, active.getLast());
            }
        }
        assertEquals(3, builds.get());
        assertEquals(600, active.size());
    }

    @Test void realShaderReloadCannotReuseOldDimensionDefinitions() {
        var cache = new DimensionShaderPacks<String, String>();
        List<String> active = new ArrayList<>();
        cache.select("nether", () -> "old settings", active::add);
        cache.clear();
        cache.select("nether", () -> "new settings", active::add);
        assertEquals(List.of("old settings", "new settings"), active);
    }

    @Test void failedOrSkippedBuildNeverPoisonsFutureSelection() {
        var cache = new DimensionShaderPacks<String, String>();
        List<String> active = new ArrayList<>();
        cache.select("nether", () -> null, active::add);
        assertThrows(IllegalStateException.class,
            () -> cache.select("nether", () -> { throw new IllegalStateException("compile"); }, active::add));
        assertTrue(active.isEmpty());
        cache.select("nether", () -> "recovered", active::add);
        assertEquals(List.of("recovered"), active);
    }

    @Test void failedActivationIsNotCached() {
        var cache = new DimensionShaderPacks<String, String>();
        assertThrows(IllegalStateException.class,
            () -> cache.select("end", () -> "bad", pack -> { throw new IllegalStateException("activate"); }));
        List<String> active = new ArrayList<>();
        cache.select("end", () -> "good", active::add);
        assertEquals(List.of("good"), active);
    }

    @Test void optionalOrUnverifiedVersionsDoNotApplyTheMixins() {
        assertTrue(EuphoriaCompatibility.supports("1.10.5-r5.9.3-neoforge", "1.8.14-beta.1+mc1.21.1"));
        assertFalse(EuphoriaCompatibility.supports(null, "1.8.14-beta.1+mc1.21.1"));
        assertFalse(EuphoriaCompatibility.supports("1.10.5-r5.9.3-neoforge", null));
        assertFalse(EuphoriaCompatibility.supports("1.11.0", "1.8.14-beta.1+mc1.21.1"));
        assertFalse(EuphoriaCompatibility.supports("1.10.5-r5.9.3-neoforge", "1.9.0"));
    }
}
