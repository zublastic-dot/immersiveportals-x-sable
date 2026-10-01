package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
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
        assertNull(region.vanilla());
        assertNull(new PortalLighting.Region(null,MIN,values(.3f),values(.1f)).vanilla());
        assertThrows(UnsupportedOperationException.class,()->region.offsets().clear());
    }
    @Test void differentOccupancyBetweenBanksIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new PortalLighting.Region(null,MIN,values(.3f),Map.of()));
    }
    private static PortalLightPalette palette(int pixel) {
        int[] pixels = new int[256]; java.util.Arrays.fill(pixels,pixel);
        return new PortalLightPalette(pixels);
    }
    @Test void transportedCoordinatesOrPaletteChangesRequirePublicationEvenWhenOffsetsMatch() {
        var nativePalette=palette(0xff112233); var reference=palette(0xff223344);
        var data=new PortalLighting.VanillaData(Map.of(MIN,new float[]{9,12,.5f}),nativePalette,reference);
        var region=new PortalLighting.Region(null,MIN,values(.5f),values(-.2f),data);
        assertTrue(region.matches(MIN,values(.5f),values(-.2f),
            new PortalLighting.VanillaData(Map.of(MIN,new float[]{9,12,.5f}),nativePalette,reference)));
        for (float[] changed : new float[][]{{8,12,.5f},{9,11,.5f},{9,12,.6f}})
            assertFalse(region.matches(MIN,values(.5f),values(-.2f),
                new PortalLighting.VanillaData(Map.of(MIN,changed),nativePalette,reference)));
        assertFalse(region.matches(MIN,values(.5f),values(-.2f),
            new PortalLighting.VanillaData(data.cells(),palette(0xff112234),reference)));
        assertFalse(region.matches(MIN,values(.5f),values(-.2f),
            new PortalLighting.VanillaData(data.cells(),nativePalette,palette(0xff223345))));
        assertFalse(region.matches(MIN,values(.5f),values(-.2f),null));
        assertFalse(new PortalLighting.Region(null,MIN,values(.5f),values(-.2f))
            .matches(MIN,values(.5f),values(-.2f),data));
    }
    @Test void vanillaMetadataCopiesInputsAndRejectsInvalidOrMismatchedCells() {
        var nativePalette=palette(0xff112233); var reference=palette(0xff223344);
        float[] cell={9,12,.5f}; var mutable=new HashMap<PortalLightField.Pos,float[]>(); mutable.put(MIN,cell);
        var data=new PortalLighting.VanillaData(mutable,nativePalette,reference);
        cell[0]=1; mutable.clear();
        assertArrayEquals(new float[]{9,12,.5f},data.cells().get(MIN));
        assertThrows(UnsupportedOperationException.class,()->data.cells().clear());
        assertThrows(IllegalArgumentException.class,()->new PortalLighting.Region(null,MIN,Map.of(),Map.of(),data));
        for (float[] invalid : new float[][]{{1,2},{-1,2,.5f},{1,16,.5f},{1,2,1.01f},
                {Float.NaN,2,.5f},{1,Float.POSITIVE_INFINITY,.5f},{1,2,Float.NEGATIVE_INFINITY}})
            assertThrows(IllegalArgumentException.class,()->new PortalLighting.VanillaData(Map.of(MIN,invalid),nativePalette,reference));
    }
    @Test void vanillaReferenceGateAcceptsOnlyTheKnownPairWithStableLowerOverworldAmbient() {
        assertTrue(PortalLighting.vanillaPair(Level.OVERWORLD,Level.NETHER,
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.NETHER_EFFECTS,0,.1f,false,false));
        assertTrue(PortalLighting.vanillaPair(Level.NETHER,Level.OVERWORLD,
            BuiltinDimensionTypes.NETHER_EFFECTS,BuiltinDimensionTypes.OVERWORLD_EFFECTS,.1f,0,false,false));
        assertFalse(PortalLighting.vanillaPair(Level.OVERWORLD,Level.END,
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.END_EFFECTS,0,.1f,false,false));
        assertFalse(PortalLighting.vanillaPair(Level.OVERWORLD,Level.OVERWORLD,
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.OVERWORLD_EFFECTS,0,.1f,false,false));
        assertFalse(PortalLighting.vanillaPair(Level.OVERWORLD,Level.NETHER,
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.END_EFFECTS,0,.1f,false,false));
        for (boolean sourceBright : new boolean[]{false,true})
            assertFalse(PortalLighting.vanillaPair(Level.OVERWORLD,Level.NETHER,
                BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.NETHER_EFFECTS,0,.1f,!sourceBright,sourceBright));
        for (float overworldAmbient : new float[]{.1f,.2f,Float.NaN,Float.POSITIVE_INFINITY})
            assertFalse(PortalLighting.vanillaPair(Level.OVERWORLD,Level.NETHER,
                BuiltinDimensionTypes.OVERWORLD_EFFECTS,BuiltinDimensionTypes.NETHER_EFFECTS,overworldAmbient,.1f,false,false));
    }
}
