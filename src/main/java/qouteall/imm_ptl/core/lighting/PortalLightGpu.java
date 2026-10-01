package qouteall.imm_ptl.core.lighting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** One atlas per loaded dimension; bindings have explicit draw-scoped restoration. */
public final class PortalLightGpu {
    private record Atlas(int texture,long revision,List<PortalLighting.Region> regions) {}
    private static final Map<ClientLevel,Atlas> ATLASES=new IdentityHashMap<>();
    public interface Binding extends AutoCloseable { @Override void close(); }
    private static final Binding EMPTY=()->{};
    private PortalLightGpu() {}
    public static void clear() {
        for(var atlas:ATLASES.values()) glDeleteTextures(atlas.texture);
        ATLASES.clear();
    }
    public static void retain(Collection<ClientLevel> worlds) {
        ATLASES.entrySet().removeIf(entry -> {
            if(worlds.contains(entry.getKey())) return false;
            glDeleteTextures(entry.getValue().texture);return true;
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
        if(regions.isEmpty()) return EMPTY;
        int unit=unusedUnit(program);
        if(unit<0) return EMPTY;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0+unit);
        int previous=glGetInteger(GL_TEXTURE_BINDING_3D), sampler=glGetInteger(GL_SAMPLER_BINDING);
        try {
            Atlas atlas=ATLASES.get(level);
            if(atlas==null || atlas.revision!=PortalLighting.revision()) {
                int texture=atlas==null?glGenTextures():atlas.texture;
                glBindTexture(GL_TEXTURE_3D,texture);
                var values=BufferUtils.createFloatBuffer(32*32*128*4);
                for(int i=0;i<regions.size();i++) {
                    var region=regions.get(i); var min=region.min();
                    for(var entry:region.offsets().entrySet()) {
                        var p=entry.getKey(); int x=p.x()-min.x(),y=p.y()-min.y(),z=p.z()-min.z()+i*32;
                        int offset=((z*32+y)*32+x)*4; float[] rgb=entry.getValue();
                        values.put(offset,rgb[0]);values.put(offset+1,rgb[1]);values.put(offset+2,rgb[2]);values.put(offset+3,1);
                    }
                }
                int unpackBuffer=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
                int[] keys={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_IMAGE_HEIGHT,GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES};
                int[] unpack=new int[keys.length];
                for(int i=0;i<keys.length;i++) unpack[i]=glGetInteger(keys[i]);
                try {
                    glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                    for(int key:keys) glPixelStorei(key,key==GL_UNPACK_ALIGNMENT?4:0);
                    glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,32,32,128,0,GL_RGBA,GL_FLOAT,values);
                } finally {
                    for(int i=0;i<keys.length;i++) glPixelStorei(keys[i],unpack[i]);
                    glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);
                }
                glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
                atlas=new Atlas(texture,PortalLighting.revision(),regions);ATLASES.put(level,atlas);
            }
            glBindTexture(GL_TEXTURE_3D,atlas.texture);glBindSampler(unit,0);
            glUniform1i(glGetUniformLocation(program,"ipPortalLightAtlas"),unit);
            glUniform1i(count,atlas.regions.size());
            for(int i=0;i<atlas.regions.size();i++) {
                var min=atlas.regions.get(i).min();
                glUniform3f(glGetUniformLocation(program,"ipPortalLightOrigin["+i+"]"),(float)(min.x()-camera.x),(float)(min.y()-camera.y),(float)(min.z()-camera.z));
            }
        } catch(RuntimeException e) {
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
