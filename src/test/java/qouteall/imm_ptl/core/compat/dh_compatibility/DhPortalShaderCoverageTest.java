package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalShaderCoverageTest {
    @AfterEach void restore() { DhPortalShaderCoverage.enable(); }

    @Test void representativeOffsetRemoteLoaderLeavesNoHoleAtItsNearestEdge() {
        double ready = DhVanillaCoverage.blocks(7, 17.8, 28.9,
            (x,z)->x>=-2 && x<=2 && z>=-1 && z<=3);
        assertEquals(30.2, ready, 1e-8);
        assertEquals(29.2, DhPortalShaderCoverage.fadeEnd(112,ready), 1e-8);
        assertTrue(DhPortalShaderCoverage.fadeEnd(112,ready) < ready);
        assertTrue(112*.4 > ready, "The installed pack's original fade begins beyond available vanilla terrain");
    }
    @Test void loadedButUnbuiltCentreAndInteriorHoleReduceCoverage() {
        AtomicInteger count = new AtomicInteger();
        assertEquals(0, DhVanillaCoverage.blocks(7,8,8,(x,z)-> { count.incrementAndGet(); return false; }));
        assertEquals(1,count.get(),"A missing centre must not scan the entire square");
        assertEquals(24, DhVanillaCoverage.blocks(7,8,8,(x,z)->x!=2 || z!=0));
        assertEquals(0,DhPortalShaderCoverage.fadeEnd(112,0));
        assertEquals(0,DhPortalShaderCoverage.fadeEnd(112,.5));
    }
    @Test void completeCoverageKeepsOriginalFadeAndScanRemainsBounded() {
        AtomicInteger count = new AtomicInteger();
        assertEquals(512,DhVanillaCoverage.blocks(1000,8,8,(x,z)-> { count.incrementAndGet(); return true; }));
        assertTrue(count.get()<=65*65);
        assertEquals(67.2,DhPortalShaderCoverage.fadeEnd(112,112),1e-8);
        assertEquals(67.2,DhPortalShaderCoverage.fadeEnd(112,-1),1e-8);
        assertEquals(64,DhVanillaCoverage.radius(64,8,8,(x,z)->true),
            "The shader scan bound must not change the established no-shader radius");
    }
    @Test void mainNestedAndShadowPassesAreExplicitlyInactive() {
        assertTrue(DhPortalShaderCoverage.eligible(1,false,false));
        assertFalse(DhPortalShaderCoverage.eligible(0,false,false));
        assertFalse(DhPortalShaderCoverage.eligible(2,false,false));
        assertFalse(DhPortalShaderCoverage.eligible(1,true,false));
        assertFalse(DhPortalShaderCoverage.eligible(1,false,true));
    }
    @Test void portalScopeRestoresAfterNestedPassAndFailure() {
        assertEquals(-1,DhPortalShaderCoverage.current());
        try (var outer=DhPortalShaderCoverage.enter(()->30.2)) {
            assertEquals(30.2,DhPortalShaderCoverage.current());
            assertThrows(IllegalStateException.class,()-> {
                try(var nested=DhPortalShaderCoverage.enter(()->-1)) {
                    assertEquals(-1,DhPortalShaderCoverage.current());throw new IllegalStateException();
                }
            });
            assertEquals(30.2,DhPortalShaderCoverage.current());
        }
        assertEquals(-1,DhPortalShaderCoverage.current());
    }
    @Test void diagnosticDisableIsBoundedAndDoesNotLoseScopedSampling() {
        assertThrows(IllegalArgumentException.class,()->DhPortalShaderCoverage.disableForSeconds(0));
        assertThrows(IllegalArgumentException.class,()->DhPortalShaderCoverage.disableForSeconds(61));
        assertFalse(DhPortalShaderCoverage.enabled(100,101));
        assertTrue(DhPortalShaderCoverage.enabled(101,101));
        assertTrue(DhPortalShaderCoverage.enabled(-10,0));
        AtomicInteger samples=new AtomicInteger();
        try(var scope=DhPortalShaderCoverage.enter(()-> { samples.incrementAndGet();return 25; })) {
            DhPortalShaderCoverage.disableForSeconds(60);
            assertEquals(-1,DhPortalShaderCoverage.current());assertEquals(1,samples.get());
            DhPortalShaderCoverage.enable();assertEquals(25,DhPortalShaderCoverage.current());
        }
    }
}
