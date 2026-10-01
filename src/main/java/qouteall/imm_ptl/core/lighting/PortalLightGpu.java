package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** One atlas per loaded dimension; bindings have explicit draw-scoped restoration. */
public final class PortalLightGpu {
    private static final int EDGE=32, SLOTS=4, BANKS=2, SLOT_FLOATS=EDGE*EDGE*EDGE*4;
    private static final int[] UNPACK_KEYS={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_IMAGE_HEIGHT,
        GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES};
    // All atlas work runs on the render thread. Reuse one slot-sized staging buffer
    // across dimensions instead of allocating a full atlas on every field revision.
    private static FloatBuffer staging;

    static final class Atlas implements AutoCloseable {
        final int texture=glGenTextures();
        private boolean allocated;
        private boolean contentsValid=true;
        private long revision=Long.MIN_VALUE;
        private List<PortalLighting.Region> regions=List.of();

        /** Returns dirty region slots; all offsets, scalar metadata and palettes publish together. */
        int update(long nextRevision,List<PortalLighting.Region> nextRegions) {
            if(nextRegions.size()>SLOTS) throw new IllegalArgumentException("Too many portal light regions");
            if(contentsValid && revision==nextRevision) return 0;
            List<PortalLighting.Region> next=List.copyOf(nextRegions);
            int dirty=0;
            for(int i=0;i<Math.max(regions.size(),next.size());i++) {
                var oldRegion=i<regions.size()?regions.get(i):null;
                var newRegion=i<next.size()?next.get(i):null;
                // Region instances are immutable publications. A revision in another
                // region must not upload this slot; moving/reordering a region must.
                if(!contentsValid || oldRegion!=newRegion) dirty|=1<<i;
            }
            if(dirty==0) { revision=nextRevision;regions=next;contentsValid=true;return 0; }

            int previous=glGetInteger(GL_TEXTURE_BINDING_3D);
            int unpackBuffer=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int[] unpack=new int[UNPACK_KEYS.length];
            for(int i=0;i<UNPACK_KEYS.length;i++) unpack[i]=glGetInteger(UNPACK_KEYS[i]);
            try {
                glBindTexture(GL_TEXTURE_3D,texture);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                for(int key:UNPACK_KEYS) glPixelStorei(key,key==GL_UNPACK_ALIGNMENT?4:0);
                if(!allocated) {
                    // GL 3.3 baseline: allocate once, then replace complete 32-cube slots.
                    // Unoccupied slots are never sampled; each newly occupied slot is
                    // fully initialized by the upload below before count is published.
                    glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,EDGE,EDGE*2,EDGE*SLOTS*BANKS,0,GL_RGBA,GL_FLOAT,(FloatBuffer)null);
                    glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
                    allocated=true;
                }
                for(int i=0;i<SLOTS;i++) if((dirty&(1<<i))!=0) {
                    if(staging==null) staging=BufferUtils.createFloatBuffer(SLOT_FLOATS);
                    for(int bank=0;bank<BANKS;bank++) for(int half=0;half<2;half++) {
                        staging.clear();
                        MemoryUtil.memSet(MemoryUtil.memAddress(staging),0,(long)SLOT_FLOATS*Float.BYTES);
                        if(i<next.size()) {
                            var region=next.get(i);
                            if(half==0) fill(staging,region.min(),bank==0?region.offsets():region.ambientOffsets(),false);
                            else if(region.vanilla()!=null) {
                                var vanilla=region.vanilla();
                                if(bank==0) fill(staging,region.min(),vanilla.cells(),true);
                                else {
                                    fillPalette(staging,vanilla.nativePalette(),0);
                                    fillPalette(staging,vanilla.referencePalette(),1);
                                }
                            }
                        }
                        // y=0..31: total/ambient banks. y=32..63: scalar cells at
                        // z=0..127; native/reference 16x16 palettes at each upper
                        // slot's first two slices. Clear every unused texel and alpha,
                        // including legacy/null metadata, with one reused 32-cube buffer.
                        glTexSubImage3D(GL_TEXTURE_3D,0,0,half*EDGE,(bank*SLOTS+i)*EDGE,EDGE,EDGE,EDGE,GL_RGBA,GL_FLOAT,staging);
                    }
                }
            } catch(RuntimeException | Error failure) {
                // An earlier slot may already have uploaded. Force the next attempt
                // to repair every visible slot, even if the caller returns to old data.
                contentsValid=false;
                throw failure;
            } finally {
                for(int i=0;i<UNPACK_KEYS.length;i++) glPixelStorei(UNPACK_KEYS[i],unpack[i]);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);
                glBindTexture(GL_TEXTURE_3D,previous);
            }
            revision=nextRevision;regions=next;contentsValid=true;
            return Integer.bitCount(dirty);
        }

        private static void fill(FloatBuffer values,PortalLightField.Pos min,Map<PortalLightField.Pos,float[]> offsets,boolean scalar) {
            for(var entry:offsets.entrySet()) {
                var p=entry.getKey(); int x=p.x()-min.x(),y=p.y()-min.y(),z=p.z()-min.z();
                float[] rgb=entry.getValue();
                if(x<0 || x>=EDGE || y<0 || y>=EDGE || z<0 || z>=EDGE || rgb.length!=3)
                    throw new IllegalArgumentException("Invalid portal light atlas cell");
                for(float channel:rgb) if(!Float.isFinite(channel))
                    throw new IllegalArgumentException("Non-finite portal light atlas offset");
                if(scalar && (rgb[0]<0 || rgb[0]>15 || rgb[1]<0 || rgb[1]>15 || rgb[2]<0 || rgb[2]>1))
                    throw new IllegalArgumentException("Invalid portal light scalar metadata");
                put(values,x,y,z,rgb);

            }
        }

        private static void fillPalette(FloatBuffer values,PortalLightPalette palette,int slice) {
            for(int sky=0;sky<16;sky++) for(int block=0;block<16;block++)
                put(values,block,sky,slice,palette.rgb(sky,block));
        }

        private static void put(FloatBuffer values,int x,int y,int z,float[] rgb) {
            int offset=((z*EDGE+y)*EDGE+x)*4;
            values.put(offset,rgb[0]);values.put(offset+1,rgb[1]);values.put(offset+2,rgb[2]);values.put(offset+3,1);
        }

        @Override public void close() { glDeleteTextures(texture); }
    }
    private static final Map<ClientLevel,Atlas> ATLASES=new IdentityHashMap<>();
    public interface Binding extends AutoCloseable { @Override void close(); }
    private static final Binding EMPTY=()->{};
    private PortalLightGpu() {}
    public static void clear() {
        for(var atlas:ATLASES.values()) atlas.close();
        ATLASES.clear();
    }
    public static void retain(Collection<ClientLevel> worlds) {
        ATLASES.entrySet().removeIf(entry -> {
            if(worlds.contains(entry.getKey())) return false;
            entry.getValue().close();return true;
        });
    }
    public static Binding bind() {
        Minecraft mc=Minecraft.getInstance();
        // DH also binds programs outside a world (resource reload/teardown).
        if(mc==null) return EMPTY;
        if(mc.level==null) {
            int program=glGetInteger(GL_CURRENT_PROGRAM);
            if(program!=0) { int count=glGetUniformLocation(program,"ipPortalLightCount");if(count>=0)glUniform1i(count,0); }
            return EMPTY;
        }
        return bind(mc.level,mc.gameRenderer.getMainCamera().getPosition());
    }
    public static Binding bind(ClientLevel level,Vec3 camera) {
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        if(program==0) return EMPTY;
        int count=glGetUniformLocation(program,"ipPortalLightCount");
        if(count<0) return EMPTY;
        glUniform1i(count,0);
        List<PortalLighting.Region> regions=PortalLighting.regions(level);
        long revision=PortalLighting.revision(level);
        Atlas atlas=ATLASES.get(level);
        if(regions.isEmpty()) {
            if(atlas!=null) atlas.update(revision,regions);
            return EMPTY;
        }
        int unit=unusedUnit(program);
        if(unit<0) return EMPTY;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0+unit);
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D), sampler=glGetInteger(GL_SAMPLER_BINDING);
        boolean created=atlas==null;
        try {
            if(created) atlas=new Atlas();
            atlas.update(revision,regions);
            if(created) { ATLASES.put(level,atlas);created=false; }
            glBindTexture(GL_TEXTURE_3D,atlas.texture);glBindSampler(unit,0);
            glUniform1i(glGetUniformLocation(program,"ipPortalLightAtlas"),unit);
            glUniform1i(count,atlas.regions.size());
            for(int i=0;i<atlas.regions.size();i++) {
                var min=atlas.regions.get(i).min();
                glUniform3f(glGetUniformLocation(program,"ipPortalLightOrigin["+i+"]"),(float)(min.x()-camera.x),(float)(min.y()-camera.y),(float)(min.z()-camera.z));
            }
        } catch(RuntimeException | Error e) {
            if(created && atlas!=null) atlas.close();
            glBindTexture(GL_TEXTURE_3D,previous);glBindSampler(unit,sampler);glUniform1i(count,0);throw e;
        } finally { glActiveTexture(active); }
        return () -> { int current=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_3D,previous);glBindSampler(unit,sampler);glActiveTexture(current); };
    }
    private static int unusedUnit(int program) {
        var occupied=new HashSet<Integer>();
        var size=BufferUtils.createIntBuffer(1); var type=BufferUtils.createIntBuffer(1);
        for(int i=0;i<glGetProgrami(program,GL_ACTIVE_UNIFORMS);i++) {
            String name=glGetActiveUniform(program,i,size,type);
            if(name.equals("ipPortalLightAtlas")) continue;
            // Reserve every sampler type, including arrays, integer and shadow samplers.
            if(isSampler(type.get(0))) for(int j=0;j<size.get(0);j++) {
                String element=size.get(0)>1?name.replace("[0]","["+j+"]"):name;
                int location=glGetUniformLocation(program,element);
                if(location>=0) occupied.add(glGetUniformi(program,location));
            }
        }
        for(int unit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)-1;unit>=0;unit--) if(!occupied.contains(unit)) return unit;
        return -1;
    }
    private static boolean isSampler(int type) {
        return switch(type) {
            case GL_SAMPLER_1D,GL_SAMPLER_2D,GL_SAMPLER_3D,GL_SAMPLER_CUBE,GL_SAMPLER_1D_SHADOW,GL_SAMPLER_2D_SHADOW,
                GL_SAMPLER_1D_ARRAY,GL_SAMPLER_2D_ARRAY,GL_SAMPLER_1D_ARRAY_SHADOW,GL_SAMPLER_2D_ARRAY_SHADOW,GL_SAMPLER_CUBE_SHADOW,
                GL_INT_SAMPLER_1D,GL_INT_SAMPLER_2D,GL_INT_SAMPLER_3D,GL_INT_SAMPLER_CUBE,GL_INT_SAMPLER_1D_ARRAY,GL_INT_SAMPLER_2D_ARRAY,
                GL_UNSIGNED_INT_SAMPLER_1D,GL_UNSIGNED_INT_SAMPLER_2D,GL_UNSIGNED_INT_SAMPLER_3D,GL_UNSIGNED_INT_SAMPLER_CUBE,GL_UNSIGNED_INT_SAMPLER_1D_ARRAY,GL_UNSIGNED_INT_SAMPLER_2D_ARRAY,
                GL_SAMPLER_2D_RECT,GL_SAMPLER_2D_RECT_SHADOW,GL_INT_SAMPLER_2D_RECT,GL_UNSIGNED_INT_SAMPLER_2D_RECT,
                GL_SAMPLER_BUFFER,GL_INT_SAMPLER_BUFFER,GL_UNSIGNED_INT_SAMPLER_BUFFER,
                GL_SAMPLER_2D_MULTISAMPLE,GL_INT_SAMPLER_2D_MULTISAMPLE,GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE,
                GL_SAMPLER_2D_MULTISAMPLE_ARRAY,GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY -> true;
            default -> false;
        };
    }
}
