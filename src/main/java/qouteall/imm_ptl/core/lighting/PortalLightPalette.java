package qouteall.imm_ptl.core.lighting;

/** Uses the actual client lightmap, including time, weather, gamma and status effects.
 * This lightmap-space model is deliberately separate from a shader pack's HDR/GI model. */
public final class PortalLightPalette {
    private final int[] abgr;
    public PortalLightPalette(int[] pixels) {
        if (pixels.length != 256) throw new IllegalArgumentException("16 by 16 lightmap required");
        abgr=pixels.clone();
    }
    public float[] rgb(int sky, int block) {
        int p=abgr[Math.clamp(sky,0,15)*16+Math.clamp(block,0,15)];
        return new float[]{(p&255)/255f,((p>>>8)&255)/255f,((p>>>16)&255)/255f};
    }
    public float[] gain(PortalLightPalette incoming, int sky, int block, int remoteSky, int remoteBlock) {
        float[] nativeLight=rgb(sky,block), floor=rgb(0,0), imported=incoming.rgb(remoteSky,remoteBlock), remoteFloor=incoming.rgb(0,0);
        float[] gain=new float[3];
        for (int i=0;i<3;i++) {
            // Remove only the native zero-light floor. Preserve the measured contribution
            // of local sources, then add the light arriving through the aperture.
            // A dimension's ambient floor is not a luminous block. It must not become
            // a source in the reverse direction either (dark Nether -> Overworld).
            float desired=Math.min(1,Math.max(0,nativeLight[i]-floor[i])
                +Math.max(0,imported[i]-remoteFloor[i])+Math.min(floor[i],remoteFloor[i]));
            gain[i]=desired/Math.max(1/255f,nativeLight[i]);
        }
        return gain;
    }
}
