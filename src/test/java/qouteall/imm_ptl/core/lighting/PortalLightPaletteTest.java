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

    private PortalLightPalette palette(int red, int green, int blue, int[][] entries) {
        int[] pixels = new int[256]; Arrays.fill(pixels, pixel(red, green, blue));
        for (int[] entry : entries) pixels[entry[0] * 16 + entry[1]] = pixel(entry[2], entry[3], entry[4]);
        return new PortalLightPalette(pixels);
    }
    private float[] vanillaCandidate(PortalLightPalette nativePalette, PortalLightPalette incoming,
                                     PortalLightPalette reference, int nativeSky, int nativeBlock,
                                     int remoteSky, int remoteBlock, float weight) {
        float[] result = nativePalette.rgb(nativeSky, nativeBlock);
        float[] ambient = nativePalette.ambientOffset(incoming, remoteSky, weight);
        float[] block = nativePalette.vanillaBlockOffset(reference, nativeSky, nativeBlock,
            remoteSky, remoteBlock, weight);
        for (int c = 0; c < 3; c++) result[c] += ambient[c] + block[c];
        return result;
    }
    @Test void warmFloorSubtractionCannotTurnASaturatedWhiteEmitterBlue() {
        // Vanilla 1.21.1 at gamma .5, midnight, no flicker: both B14 entries
        // saturate to white, but their dark baselines differ significantly.
        var nether = palette(98,80,67,new int[][]{{0,14,252,252,252}});
        var overworld = palette(25,25,25,new int[][]{{0,14,252,252,252}});
        float[] old = nether.rgb(0,14), ambient = nether.ambientOffset(overworld,0,1);
        for (int c=0;c<3;c++) old[c] += ambient[c];
        assertArrayEquals(new float[]{179/255f,197/255f,210/255f},old,1e-6f);
        assertArrayEquals(overworld.rgb(0,14),vanillaCandidate(nether,overworld,overworld,0,14,0,0,1),1e-6f);
    }
    @Test void noBlockLightLeavesTheIndependentAmbientReplacementUnchanged() {
        var nether = palette(98,80,67,new int[][]{{7,0,110,100,100}});
        var overworld = palette(25,25,25,new int[][]{{12,0,150,180,200}});
        for (float weight : new float[]{0,.25f,1}) {
            assertArrayEquals(new float[3],nether.vanillaBlockOffset(overworld,7,0,12,0,weight),1e-6f);
            float[] expected = nether.rgb(7,0), ambient = nether.ambientOffset(overworld,12,weight);
            for (int c=0;c<3;c++) expected[c] += ambient[c];
            assertArrayEquals(expected,vanillaCandidate(nether,overworld,overworld,7,0,12,0,weight),1e-6f);
        }
    }
    @Test void reverseDirectionKeepsOverworldEmittersWhenNetherHasNoStrongerLight() {
        var overworld = palette(25,25,25,new int[][]{{0,12,221,212,194},{12,0,150,180,200},{12,12,240,242,248}});
        var nether = palette(98,80,67,new int[][]{{0,12,231,225,213}});
        for (int sky : new int[]{0,12}) for (int remoteBlock : new int[]{0,8,12})
            assertArrayEquals(overworld.rgb(sky,12),
                vanillaCandidate(overworld,nether,overworld,sky,12,0,remoteBlock,1),1e-6f);
    }
    @Test void localAndTransportedBlockLevelsComposeByMaximumWithoutAddingLightTwice() {
        var nether = palette(98,80,67,new int[][]{{0,8,178,160,130},{0,12,231,225,213}});
        var overworld = palette(25,25,25,new int[][]{{0,8,151,129,96},{0,12,221,212,194}});
        for (int[] levels : new int[][]{{12,8},{8,12},{12,12}})
            assertArrayEquals(overworld.rgb(0,12),
                vanillaCandidate(nether,overworld,overworld,0,levels[0],0,levels[1],1),1e-6f);
    }
    @Test void blockResponseUsesEffectiveDaytimeSkyInsteadOfAZeroSkyLookup() {
        var nether = palette(98,80,67,new int[][]{{0,8,178,160,130}});
        var overworld = palette(25,25,25,new int[][]{{0,8,151,129,96},{12,0,150,180,200},{12,8,225,230,240}});
        // The same emitter has a smaller display-RGB increment on a bright sky
        // baseline because the captured lightmap already includes gamma/clipping.
        assertArrayEquals(overworld.rgb(12,8),
            vanillaCandidate(nether,overworld,overworld,0,8,12,0,1),1e-6f);
        assertFalse(Arrays.equals(nether.vanillaBlockOffset(overworld,0,8,0,0,1),
            nether.vanillaBlockOffset(overworld,0,8,12,0,1)));
    }
    @Test void currentNativeSkyRowIsRemovedBeforeInstallingTheReferenceResponse() {
        var nether = palette(98,80,67,new int[][]{{9,0,120,130,140},{9,8,200,190,180}});
        var overworld = palette(25,25,25,new int[][]{{0,8,151,129,96},{9,0,100,120,140},{9,8,210,215,220}});
        assertArrayEquals(new float[]{30/255f,35/255f,40/255f},
            nether.vanillaBlockOffset(overworld,9,8,0,0,1),1e-6f);
    }
    @Test void replacementWeightInterpolatesTheResponseAndZeroLeavesNativeLightExact() {
        var nether = palette(98,80,67,new int[][]{{0,12,231,225,213}});
        var overworld = palette(25,25,25,new int[][]{{0,12,221,212,194}});
        for (float weight : new float[]{0,.25f,.7f,1}) {
            float[] expected = nether.rgb(0,12), target = overworld.rgb(0,12);
            for (int c=0;c<3;c++) expected[c] += weight * (target[c]-expected[c]);
            assertArrayEquals(expected,vanillaCandidate(nether,overworld,overworld,0,12,0,0,weight),1e-6f);
        }
    }
}
