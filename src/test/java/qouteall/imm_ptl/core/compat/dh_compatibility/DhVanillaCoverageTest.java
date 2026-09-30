package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhVanillaCoverageTest {
    private static DhVanillaCoverage.Loaded square(int radius) {
        return (x,z) -> Math.abs(x) <= radius && Math.abs(z) <= radius;
    }
    @Test void delayedUnloadShrinksTransitionOnlyWhenChunksDisappear() {
        assertEquals(7, DhVanillaCoverage.radius(7,8,8,square(7)));
        // IP's retained outer chunks expire after several tracking generations.
        assertEquals(4, DhVanillaCoverage.radius(7,8,8,square(4)));
        assertEquals(7, DhVanillaCoverage.radius(7,8,8,square(7)));
    }
    @Test void cameraOffsetAccountsForPortalCentredLoading() {
        assertEquals(3, DhVanillaCoverage.radius(7,-5,8,square(4)));
        assertEquals(3, DhVanillaCoverage.radius(7,21,8,square(4)));
    }
    @Test void internalMissingChunkBoundsCoverageEvenWithOuterChunksPresent() {
        assertEquals(2, DhVanillaCoverage.radius(7,8,8,(x,z)->x!=3||z!=0));
    }
    @Test void missingCentreAndNegativeCoordinatesStayBounded() {
        assertEquals(1, DhVanillaCoverage.radius(7,-.1,-.1,(x,z)->false));
        assertEquals(4, DhVanillaCoverage.radius(7,-8,-8,(x,z)->Math.abs(x+1)<=4&&Math.abs(z+1)<=4));
    }
    @Test void completeCoverageAndSmallRequestedDistanceArePreserved() {
        assertEquals(7, DhVanillaCoverage.radius(7,8,8,(x,z)->true));
        assertEquals(1, DhVanillaCoverage.radius(1,8,8,(x,z)->false));
    }
}
