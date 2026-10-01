package qouteall.imm_ptl.core.lighting;

/** Uses the actual client lightmap, including time, weather, gamma and status effects.
 * This lightmap-space model is deliberately separate from a shader pack's HDR/GI model. */
public final class PortalLightPalette {
    private final int[] abgr;
    public PortalLightPalette(int[] pixels) {
        if (pixels.length != 256) throw new IllegalArgumentException("16 by 16 lightmap required");
        abgr=pixels.clone();
    }
    public boolean matches(int[] pixels) { return java.util.Arrays.equals(abgr, pixels); }
    public float[] rgb(int sky, int block) {
        int p=abgr[Math.clamp(sky,0,15)*16+Math.clamp(block,0,15)];
        return new float[]{(p&255)/255f,((p>>>8)&255)/255f,((p>>>16)&255)/255f};
    }
    /** Reused for every cell sharing these two palettes, rather than allocating four RGB arrays per cell. */
    public float[][] offsetTable(PortalLightPalette incoming) {
        float[][] result = new float[256][];
        for (int sky = 0; sky < 16; sky++) for (int block = 0; block < 16; block++)
            result[sky * 16 + block] = offset(incoming, sky, block, 1);
        return result;
    }
    public float[] offset(PortalLightPalette incoming, int remoteSky, int remoteBlock, float replacement) {
        float weight=Math.clamp(replacement,0,1);
        float[] floor=rgb(0,0), imported=incoming.rgb(remoteSky,remoteBlock), remoteFloor=incoming.rgb(0,0);
        float[] offset=new float[3];
        for (int i=0;i<3;i++) {
            // Cache only the ambient replacement, never a ratio against local light.
            // Held/entity lights can change between field updates. The shader adds this
            // offset to the light actually used by the current terrain mesh, preserving
            // those contributions without a stale multiplier or a second update clock.
            // A dimension's ambient floor is not a luminous block. It must not become
            // a source in the reverse direction either (dark Nether -> Overworld).
            offset[i]=weight*(Math.max(0,imported[i]-remoteFloor[i])
                +Math.min(floor[i],remoteFloor[i])-floor[i]);
        }
        return offset;
    }
}
