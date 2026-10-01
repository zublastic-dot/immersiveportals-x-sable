package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PortalLightRegionTest {
    private static final PortalLightField.Pos MIN=new PortalLightField.Pos(0,0,0);
    private static Map<PortalLightField.Pos,float[]> values(float red) {
        return Map.of(MIN,new float[]{red,.1f,.2f});
    }
    @Test void ambientOnlyChangeRequiresPublicationEvenWhenTotalBankIsUnchanged() {
        var total=values(.5f);var ambient=values(-.2f);
        var region=new PortalLighting.Region(null,MIN,total,ambient);
        assertTrue(region.matches(MIN,values(.5f),values(-.2f)));
        assertFalse(region.matches(MIN,total,values(-.1f)));
        assertFalse(region.matches(MIN,values(.6f),ambient));
        assertFalse(region.matches(MIN.add(1,0,0),total,ambient));
    }
    @Test void compatibilityConstructorSharesBanksAndCopiesThePublishedMap() {
        var mutable=new HashMap<>(values(.3f));
        var region=new PortalLighting.Region(null,MIN,mutable);
        mutable.clear();
        assertEquals(1,region.offsets().size());
        assertSame(region.offsets(),region.ambientOffsets());
        assertThrows(UnsupportedOperationException.class,()->region.offsets().clear());
    }
    @Test void differentOccupancyBetweenBanksIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new PortalLighting.Region(null,MIN,values(.3f),Map.of()));
    }
}
