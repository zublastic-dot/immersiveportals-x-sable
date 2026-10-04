package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalColoredLightingTest {
    @Test void runtimeAndOptionalMixinAdmitOnlyTheSameVerifiedVersion() {
        assertTrue(PortalColoredLightCompatibility.supports("2.5.1"));
        for (String version : new String[]{null,"","2.5.0","2.5.2","3.0.0","2.5.1-custom"})
            assertFalse(PortalColoredLightCompatibility.supports(version));
    }
    @Test void falloffPreservesMagnitudeAndSourceRemoval() {
        assertEquals(0xee7700, PortalColoredLighting.attenuated(15,8,0,1));
        assertEquals(0x110000, PortalColoredLighting.attenuated(15,8,0,14));
        assertEquals(0, PortalColoredLighting.attenuated(15,8,0,15));
        assertEquals(0, PortalColoredLighting.attenuated(0,0,0,0));
        assertEquals(0xff0000, PortalColoredLighting.attenuated(30,Float.NaN,-1,0));
    }
    @Test void localAndIncomingMergeCannotAmplifyTheSameLight() {
        assertEquals(0xc08020, PortalColoredLighting.max(0xc02020,0x308010));
        assertEquals(0xc08020, PortalColoredLighting.max(0xc08020,0xc08020));
        assertEquals(0xc08020, PortalColoredLighting.max(0xc08020,0));
    }
    @Test void interpolationUsesCellCentersAndCannotReachAcrossOpaqueMembership() {
        Map<Pos,Integer> cells = Map.of(new Pos(0,0,0),0xff0000,new Pos(1,0,0),0x0000ff);
        assertEquals(0xff0000,PortalColoredLighting.sample(cells,.5,.5,.5));
        assertEquals(0x800080,PortalColoredLighting.sample(cells,1,.5,.5));
        assertEquals(0x0000ff,PortalColoredLighting.sample(cells,1.5,.5,.5));
        assertEquals(0,PortalColoredLighting.sample(cells,2,.5,.5));
        assertEquals(0,PortalColoredLighting.sample(cells,-.01,.5,.5));
        assertEquals(0,PortalColoredLighting.sample(cells,Double.NaN,.5,.5));
    }
    @Test void unobservedNeighborsDoNotDimAnObservedCell() {
        Map<Pos,Integer> cells = Map.of(new Pos(-1,-1,-1),0x4280ab);
        assertEquals(0x4280ab,PortalColoredLighting.sample(cells,-.1,-.1,-.1));
        assertEquals(0,PortalColoredLighting.sample(Map.of(),-.1,-.1,-.1));
    }
    @Test void dirtySectionsIncludeTheInterpolationBorderAtNegativeCoordinates() {
        Set<Pos> dirty = PortalColoredLighting.sections(Set.of(new Pos(-1,15,16), new Pos(0,16,17)));
        assertEquals(8,dirty.size());
        assertTrue(dirty.contains(new Pos(-1,0,0)));
        assertTrue(dirty.contains(new Pos(0,1,1)));
        assertEquals(Set.of(),PortalColoredLighting.sections(Set.of()));
    }
    @Test void featureDisabledReturnsNativeObjectWithoutLoadingOptionalMod() {
        boolean old=qouteall.imm_ptl.core.IPGlobal.experimentalPortalColoredLighting;
        try {
            qouteall.imm_ptl.core.IPGlobal.experimentalPortalColoredLighting=false;
            Object nativeSample=new Object();
            assertSame(nativeSample,PortalColoredLighting.merge(null,1,2,3,nativeSample));
            assertNull(PortalColoredLighting.merge(null,1,2,3,null));
            assertFalse(PortalColoredLighting.transports(null,null,Map.of()));
        } finally { qouteall.imm_ptl.core.IPGlobal.experimentalPortalColoredLighting=old; }
    }
    @Test void rgbTransportRemovesOnlyShaderScalarBlockChannel() {
        Pos p=new Pos(0,0,0), inward=new Pos(1,0,0);
        var update=PortalLightSnapshot.update(
            point->new PortalLightSnapshot.Sample(point.equals(p)?Cell.OPEN:Cell.CLOSED,new Light(0,0)),
            point->new PortalLightSnapshot.Sample(Cell.OPEN,new Light(15,15)), Map.of(p,p),inward,null);
        assertTrue(update.field().available());
        float[] vanilla=PortalShaderLighting.packCells(update.snapshot(),p,Map.of(),false);
        float[] rgb=PortalShaderLighting.packCells(update.snapshot(),p,Map.of(),true);
        assertEquals(14/15f,vanilla[1]);
        assertEquals(0,rgb[1]);
        assertEquals(vanilla[0],rgb[0]); assertEquals(vanilla[2],rgb[2]); assertEquals(vanilla[3],rgb[3]);
    }
    @Test void sourceUnloadCannotReusePreviousBrightApertureSamples() {
        Pos p=new Pos(0,0,0), inward=new Pos(1,0,0);
        PortalLightSnapshot.Reader room=point->new PortalLightSnapshot.Sample(
            point.equals(p)?Cell.OPEN:Cell.CLOSED,new Light(0,0));
        var bright=PortalLightSnapshot.update(room,
            point->new PortalLightSnapshot.Sample(Cell.OPEN,new Light(0,15)),Map.of(p,p),inward,null);
        assertEquals(14,bright.field().cells().get(p).block());
        var unloaded=PortalLightSnapshot.update(room,
            point->PortalColoredLighting.unavailableCell(true),Map.of(p,p),inward,bright.snapshot(),false);
        assertEquals(0,unloaded.field().cells().get(p).block());
        assertFalse(unloaded.usedCache());
        assertEquals(0,PortalColoredLighting.attenuated(15,0,0,15-unloaded.field().cells().get(p).block()));
    }
}
