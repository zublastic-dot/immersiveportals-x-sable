package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PortalLightPaletteTest {
    int pixel(int r,int g,int b) { return 0xff000000|(b<<16)|(g<<8)|r; }
    @Test void darkRoomReplacesWarmFloorWithIncomingNightPalette() {
        int[] nether=new int[256],night=new int[256];Arrays.fill(nether,pixel(60,42,30));Arrays.fill(night,pixel(12,16,28));
        var local=new PortalLightPalette(nether);var source=new PortalLightPalette(night);
        float[] offset=local.offset(source,11,0,1),nativeRgb=local.rgb(0,0);
        for(int c=0;c<3;c++) assertEquals(source.rgb(11,0)[c],offset[c]+nativeRgb[c],1e-6);
        assertTrue(offset[2]>offset[0]);
    }
    @Test void localLightContributionSurvivesAmbientReplacement() {
        int[] nether=new int[256],source=new int[256];Arrays.fill(nether,pixel(50,40,30));Arrays.fill(source,pixel(10,10,20));
        nether[12]=pixel(200,180,160);
        float[] offset=new PortalLightPalette(nether).offset(new PortalLightPalette(source),9,0,1);
        assertEquals(160/255f,offset[0]+200/255f,1e-6);
        assertEquals(150/255f,offset[2]+160/255f,1e-6);
    }
    @Test void timeDependentSourceChangesWithoutRebuildingWorldLight() {
        int[] local=new int[256],night=new int[256],day=new int[256];Arrays.fill(local,pixel(60,40,30));Arrays.fill(night,pixel(10,15,30));Arrays.fill(day,pixel(180,180,200));
        var palette=new PortalLightPalette(local);
        assertTrue(palette.offset(new PortalLightPalette(day),12,0,1)[0]>palette.offset(new PortalLightPalette(night),12,0,1)[0]);
    }
    @Test void copiesPixelsAndHandlesBlackWithoutNanOrInfinity() {
        int[] pixels=new int[256];var p=new PortalLightPalette(pixels);Arrays.fill(pixels,-1);
        assertArrayEquals(new float[]{0,0,0},p.rgb(0,0));
        float[] offset=p.offset(new PortalLightPalette(pixels),15,0,1);
        for(float value:offset) assertTrue(Float.isFinite(value));
    }
    @Test void darkNetherDoesNotExportItsAmbientFloorAsALightSource() {
        int[] overworld=new int[256],nether=new int[256];Arrays.fill(overworld,pixel(10,12,20));Arrays.fill(nether,pixel(60,42,30));
        var local=new PortalLightPalette(overworld);
        assertArrayEquals(new float[]{0,0,0},local.offset(new PortalLightPalette(nether),0,0,1),1e-6f);
    }
    @Test void localExposureBlendsContinuouslyAndLeavesUncorrectedTerrainIntact() {
        int[] nether=new int[256],night=new int[256];Arrays.fill(nether,pixel(60,42,30));Arrays.fill(night,pixel(12,16,28));
        var local=new PortalLightPalette(nether);var source=new PortalLightPalette(night);
        float[] full=local.offset(source,11,0,1),half=local.offset(source,11,0,.5f);
        assertArrayEquals(new float[]{0,0,0},local.offset(source,11,0,0),1e-6f);
        for(int c=0;c<3;c++) assertEquals(full[c]*.5f,half[c],1e-6);
    }
    @Test void switchingLocalLightDoesNotChangeCachedAmbientOffset() {
        int[] nether=new int[256],night=new int[256];
        Arrays.fill(nether,pixel(60,42,30));Arrays.fill(night,pixel(12,16,28));
        var source=new PortalLightPalette(night);
        float[] before=new PortalLightPalette(nether).offset(source,11,0,1);
        // A dynamic light changes all lit entries but not the zero-light baseline.
        for(int sky=0;sky<16;sky++)for(int block=1;block<16;block++) nether[sky*16+block]=pixel(220,180,100);
        assertArrayEquals(before,new PortalLightPalette(nether).offset(source,11,0,1));
    }
    @Test void cachedDeltaTablePreservesEveryPaletteEntryAndExposureWeight() {
        int[] a=new int[256],b=new int[256];
        for(int i=0;i<256;i++) { a[i]=pixel(i,255-i,i/2);b[i]=pixel(255-i,i/2,i); }
        var local=new PortalLightPalette(a);var incoming=new PortalLightPalette(b);
        var table=local.offsetTable(incoming);
        for(int sky=0;sky<16;sky++) for(int block=0;block<16;block++) for(float weight:new float[]{0,.25f,.8f,1}) {
            float[] delta=table[sky*16+block];
            assertArrayEquals(local.offset(incoming,sky,block,weight),
                new float[]{delta[0]*weight,delta[1]*weight,delta[2]*weight},1e-6f);
        }
        assertTrue(local.matches(a)); a[200]^=0xff; assertFalse(local.matches(a));
    }
    @Test void ambientBankExcludesIncomingBlockLightButRetainsSkyAndFloorReplacement() {
        int[] local=new int[256],remote=new int[256];
        Arrays.fill(local,pixel(80,50,40));Arrays.fill(remote,pixel(10,20,30));
        remote[9*16]=pixel(70,100,90);
        remote[9*16+12]=pixel(230,150,180);
        var palette=new PortalLightPalette(local);var incoming=new PortalLightPalette(remote);
        assertArrayEquals(new float[]{-5/255f,25/255f,25/255f},palette.ambientOffset(incoming,9,.5f),1e-6f);
        assertArrayEquals(new float[]{75/255f,50/255f,70/255f},palette.offset(incoming,9,12,.5f),1e-6f);
        assertArrayEquals(palette.ambientOffset(incoming,9,1),palette.offsetTable(incoming)[9*16]);
    }
    @Test void changingIncomingEmitterRowsCannotRecolorTheAmbientBank() {
        int[] local=new int[256],remote=new int[256];
        Arrays.fill(local,pixel(60,42,30));Arrays.fill(remote,pixel(12,16,28));
        for(int sky=1;sky<16;sky++) remote[sky*16]=pixel(30+sky,40+sky,60+sky);
        var palette=new PortalLightPalette(local);var old=new PortalLightPalette(remote);
        for(int sky=0;sky<16;sky++) for(int block=1;block<16;block++) remote[sky*16+block]=pixel(240,100,40);
        var changed=new PortalLightPalette(remote);
        for(int sky=0;sky<16;sky++) for(float weight:new float[]{0,.2f,1})
            assertArrayEquals(palette.ambientOffset(old,sky,weight),palette.ambientOffset(changed,sky,weight));
        assertFalse(Arrays.equals(palette.offset(old,9,12,1),palette.offset(changed,9,12,1)));
    }
}
