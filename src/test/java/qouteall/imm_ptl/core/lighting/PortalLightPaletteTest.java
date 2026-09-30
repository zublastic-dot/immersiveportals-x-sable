package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PortalLightPaletteTest {
    int pixel(int r,int g,int b) { return 0xff000000|(b<<16)|(g<<8)|r; }
    @Test void darkRoomReplacesWarmFloorWithIncomingNightPalette() {
        int[] nether=new int[256],night=new int[256];Arrays.fill(nether,pixel(60,42,30));Arrays.fill(night,pixel(12,16,28));
        var local=new PortalLightPalette(nether);var source=new PortalLightPalette(night);
        float[] gain=local.gain(source,0,0,11,0),nativeRgb=local.rgb(0,0);
        for(int c=0;c<3;c++) assertEquals(source.rgb(11,0)[c],gain[c]*nativeRgb[c],1e-6);
        assertTrue(gain[2]>gain[0]);
    }
    @Test void localLightContributionSurvivesAmbientReplacement() {
        int[] nether=new int[256],source=new int[256];Arrays.fill(nether,pixel(50,40,30));Arrays.fill(source,pixel(10,10,20));
        nether[12]=pixel(200,180,160);
        float[] gain=new PortalLightPalette(nether).gain(new PortalLightPalette(source),0,12,9,0);
        assertEquals(160/255f,gain[0]*200/255f,1e-6);
        assertEquals(150/255f,gain[2]*160/255f,1e-6);
    }
    @Test void timeDependentSourceChangesWithoutRebuildingWorldLight() {
        int[] local=new int[256],night=new int[256],day=new int[256];Arrays.fill(local,pixel(60,40,30));Arrays.fill(night,pixel(10,15,30));Arrays.fill(day,pixel(180,180,200));
        var palette=new PortalLightPalette(local);
        assertTrue(palette.gain(new PortalLightPalette(day),0,0,12,0)[0]>palette.gain(new PortalLightPalette(night),0,0,12,0)[0]);
    }
    @Test void copiesPixelsAndHandlesBlackWithoutNanOrInfinity() {
        int[] pixels=new int[256];var p=new PortalLightPalette(pixels);Arrays.fill(pixels,-1);
        assertArrayEquals(new float[]{0,0,0},p.rgb(0,0));
        float[] gain=p.gain(new PortalLightPalette(pixels),0,0,15,0);
        for(float g:gain) assertTrue(Float.isFinite(g));
    }
    @Test void darkNetherDoesNotExportItsAmbientFloorAsALightSource() {
        int[] overworld=new int[256],nether=new int[256];Arrays.fill(overworld,pixel(10,12,20));Arrays.fill(nether,pixel(60,42,30));
        var local=new PortalLightPalette(overworld);
        assertArrayEquals(new float[]{1,1,1},local.gain(new PortalLightPalette(nether),0,0,0,0),1e-6f);
    }
}
