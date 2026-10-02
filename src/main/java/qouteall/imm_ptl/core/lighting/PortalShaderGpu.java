package qouteall.imm_ptl.core.lighting;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL33.*;

/** Draw-scoped shader transport textures, independently owned by each actual receiving world. */
public final class PortalShaderGpu {
    private static final int EDGE=32, SLOTS=4, CELL_FLOATS=EDGE*EDGE*EDGE*4, SHADOW_BYTES=EDGE*EDGE;
    private static final int[] UNPACK_KEYS={GL_UNPACK_ALIGNMENT,GL_UNPACK_ROW_LENGTH,GL_UNPACK_IMAGE_HEIGHT,
        GL_UNPACK_SKIP_PIXELS,GL_UNPACK_SKIP_ROWS,GL_UNPACK_SKIP_IMAGES,GL_UNPACK_SWAP_BYTES};
    private static final PortalLightGpu.Binding EMPTY=()->{};
    private static final Map<ClientLevel,Atlas> ATLASES=new IdentityHashMap<>();
    private static Atlas inertAtlas;
    private static final Map<ClientLevel,Set<Integer>> OBSERVED_BINDINGS=new IdentityHashMap<>();
    private static int observedCount;
    // Render-thread-only staging; no full-atlas allocation on a field or sun update.
    private static FloatBuffer cellStaging;
    private static ByteBuffer shadowStaging;
    private static boolean warned;

    private PortalShaderGpu() {}

    static final class Atlas implements AutoCloseable {
        final int texture=glGenTextures(), shadowTexture=glGenTextures();
        private boolean allocated, contentsValid=true;
        private List<PortalShaderLighting.Region> regions=List.of();

        /** Payload arrays are immutable publications; direction/time-only changes do not upload. */
        int update(List<PortalShaderLighting.Region> nextRegions) {
            if(nextRegions.size()>SLOTS) throw new IllegalArgumentException("Too many shader light regions");
            List<PortalShaderLighting.Region> next=List.copyOf(nextRegions);
            for(var region:next) if(region.cells()==null || region.sourceShadow()==null)
                throw new IllegalArgumentException("Missing shader field payload");
            int cellsDirty=0, shadowsDirty=0;
            for(int i=0;i<Math.max(regions.size(),next.size());i++) {
                var old=i<regions.size()?regions.get(i):null;
                var value=i<next.size()?next.get(i):null;
                if(!contentsValid || (old==null?null:old.cells())!=(value==null?null:value.cells())) cellsDirty|=1<<i;
                if(!contentsValid || (old==null?null:old.sourceShadow())!=(value==null?null:value.sourceShadow())) shadowsDirty|=1<<i;
            }
            // A zero-count program still has active samplers. Give its inert binding complete zero textures.
            if(!allocated && next.isEmpty()) { cellsDirty=(1<<SLOTS)-1;shadowsDirty=(1<<SLOTS)-1; }
            if((cellsDirty|shadowsDirty)==0) { regions=next;return 0; }
            int previous3d=glGetInteger(GL_TEXTURE_BINDING_3D), previousArray=glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
            int unpackBuffer=glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int[] unpack=new int[UNPACK_KEYS.length];
            for(int i=0;i<UNPACK_KEYS.length;i++) unpack[i]=glGetInteger(UNPACK_KEYS[i]);
            try {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
                for(int key:UNPACK_KEYS) glPixelStorei(key,key==GL_UNPACK_ALIGNMENT?1:0);
                if(!allocated) {
                    glBindTexture(GL_TEXTURE_3D,texture);
                    glTexImage3D(GL_TEXTURE_3D,0,GL_RGBA16F,EDGE,EDGE,EDGE*SLOTS,0,GL_RGBA,GL_FLOAT,(FloatBuffer)null);
                    parameters(GL_TEXTURE_3D,GL_LINEAR);
                    glBindTexture(GL_TEXTURE_2D_ARRAY,shadowTexture);
                    glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R8,EDGE,EDGE,SLOTS,0,GL_RED,GL_UNSIGNED_BYTE,(ByteBuffer)null);
                    // Aperture texels represent blocked/clear rays. Never blur across a hard blocker.
                    parameters(GL_TEXTURE_2D_ARRAY,GL_NEAREST);
                    allocated=true;
                }
                for(int i=0;i<SLOTS;i++) {
                    if((cellsDirty&(1<<i))!=0) {
                        if(cellStaging==null) cellStaging=BufferUtils.createFloatBuffer(CELL_FLOATS);
                        cellStaging.clear();
                        if(i<next.size()) {
                            float[] values=next.get(i).cells();
                            validateCells(values);
                            cellStaging.put(values).flip();
                        } else MemoryUtil.memSet(MemoryUtil.memAddress(cellStaging),0,(long)CELL_FLOATS*Float.BYTES);
                        glBindTexture(GL_TEXTURE_3D,texture);
                        glTexSubImage3D(GL_TEXTURE_3D,0,0,0,i*EDGE,EDGE,EDGE,EDGE,GL_RGBA,GL_FLOAT,cellStaging);
                    }
                    if((shadowsDirty&(1<<i))!=0) {
                        if(shadowStaging==null) shadowStaging=BufferUtils.createByteBuffer(SHADOW_BYTES);
                        shadowStaging.clear();
                        if(i<next.size()) {
                            byte[] values=next.get(i).sourceShadow();
                            if(values.length!=SHADOW_BYTES) throw new IllegalArgumentException("Invalid shader shadow mask size");
                            shadowStaging.put(values).flip();
                        } else MemoryUtil.memSet(MemoryUtil.memAddress(shadowStaging),0,SHADOW_BYTES);
                        glBindTexture(GL_TEXTURE_2D_ARRAY,shadowTexture);
                        glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,0,i,EDGE,EDGE,1,GL_RED,GL_UNSIGNED_BYTE,shadowStaging);
                    }
                }
            } catch(RuntimeException | Error failure) {
                // A preceding slot/bank may already have changed. Repair all published slots on retry.
                contentsValid=false;
                throw failure;
            } finally {
                for(int i=0;i<UNPACK_KEYS.length;i++) glPixelStorei(UNPACK_KEYS[i],unpack[i]);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpackBuffer);
                glBindTexture(GL_TEXTURE_3D,previous3d);
                glBindTexture(GL_TEXTURE_2D_ARRAY,previousArray);
            }
            regions=next;contentsValid=true;
            return Integer.bitCount(cellsDirty|shadowsDirty);
        }

        private static void validateCells(float[] values) {
            if(values.length!=CELL_FLOATS) throw new IllegalArgumentException("Invalid shader field size");
            for(int i=0;i<values.length;i++) {
                float v=values[i];
                if(!Float.isFinite(v) || v<0 || v>1 || ((i&3)==3 && v!=0 && v!=1))
                    throw new IllegalArgumentException("Invalid shader field channel");
            }
        }

        private static void parameters(int target,int filter) {
            glTexParameteri(target,GL_TEXTURE_MIN_FILTER,filter);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,filter);
            glTexParameteri(target,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(target,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
        }

        @Override public void close() { glDeleteTextures(texture);glDeleteTextures(shadowTexture); }
    }

    public static void clear() {
        for(var atlas:ATLASES.values()) atlas.close();
        if(inertAtlas!=null) { inertAtlas.close();inertAtlas=null; }
        ATLASES.clear();OBSERVED_BINDINGS.clear();observedCount=0;warned=false;
    }

    public static void retain(Collection<ClientLevel> worlds) {
        ATLASES.entrySet().removeIf(entry->{
            if(worlds.stream().anyMatch(w->w==entry.getKey())) return false;
            entry.getValue().close();return true;
        });
        OBSERVED_BINDINGS.keySet().removeIf(world->worlds.stream().noneMatch(w->w==world));
    }

    public static PortalLightGpu.Binding bind() {
        Minecraft mc=Minecraft.getInstance();
        return bind(mc==null?null:mc.level,mc==null || mc.level==null?Vec3.ZERO:mc.gameRenderer.getMainCamera().getPosition());
    }

    public static PortalLightGpu.Binding bind(ClientLevel world,Vec3 camera) {
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        if(program==0) return EMPTY;
        int count=glGetUniformLocation(program,"ipSunCount");
        if(count<0) return EMPTY;
        glUniform1i(count,0);
        List<PortalShaderLighting.Region> regions=world==null?List.of():PortalShaderLighting.regions(world);
        Atlas atlas=ATLASES.get(world);
        if(regions.isEmpty()) return bindInert(program,world);
        boolean created=atlas==null;
        if(created) atlas=new Atlas();
        try {
            PortalLightGpu.Binding binding=bindAtlas(program,atlas,regions,()->{
                portalViewUniform(program,world);uniforms(program,regions,camera);
            });
            if(created) {
                if(binding==EMPTY) atlas.close();else ATLASES.put(world,atlas);
            }
            if(binding!=EMPTY && observedCount<64 && OBSERVED_BINDINGS.computeIfAbsent(world,w->new HashSet<>()).add(program)) {
                observedCount++;
                LogUtils.getLogger().info("[IP shader light] bound program {} for {} with {} regions",program,world.dimension().location(),regions.size());
            }
            return binding;
        } catch(RuntimeException | Error failure) {
            if(created) atlas.close();
            if(failure instanceof Error error) throw error;
            // Optional pack integration fails closed and never prevents the native draw.
            if(!warned) { warned=true;LogUtils.getLogger().warn("[IP shader light] disabled draw after binding failure",failure); }
            return bindInert(program,world);
        }
    }

    private static PortalLightGpu.Binding bindInert(int program,ClientLevel world) {
        boolean created=inertAtlas==null;
        Atlas atlas=created?new Atlas():inertAtlas;
        try {
            var binding=bindAtlas(program,atlas,List.of(),()->portalViewUniform(program,world));
            if(created) {
                if(binding==EMPTY) atlas.close();else inertAtlas=atlas;
            }
            return binding;
        } catch(RuntimeException | Error failure) {
            if(created) atlas.close();throw failure;
        }
    }

    private static void portalViewUniform(int program,ClientLevel world) {
        boolean portalView=world!=null && Minecraft.getInstance()!=null
            && Minecraft.getInstance().level==world && PortalRendering.isRendering();
        glUniform1i(glGetUniformLocation(program,"ipSunPortalView"),portalView?1:0);
    }

    /** Production binding boundary is also exercised by hidden-GL tests without a Minecraft level. */
    static PortalLightGpu.Binding bindAtlas(int program,Atlas atlas,List<PortalShaderLighting.Region> regions,Runnable uniforms) {
        int count=glGetUniformLocation(program,"ipSunCount");
        if(count<0) return EMPTY;
        glUniform1i(count,0);
        int cellsLocation=glGetUniformLocation(program,"ipSunAtlas"),shadowLocation=glGetUniformLocation(program,"ipSunSourceShadow");
        if(cellsLocation<0) return EMPTY;
        // Deferred fog/storm passes use only the field atlas; GLSL optimizes their shadow sampler out.
        int[] units=unusedUnits(program,shadowLocation>=0?2:1);
        if(units==null) return EMPTY;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        UnitBinding first=null,second=null;
        try {
            first=UnitBinding.capture(units[0],GL_TEXTURE_3D,GL_TEXTURE_BINDING_3D);
            if(shadowLocation>=0) second=UnitBinding.capture(units[1],GL_TEXTURE_2D_ARRAY,GL_TEXTURE_BINDING_2D_ARRAY);
            atlas.update(regions);
            glActiveTexture(GL_TEXTURE0+units[0]);glBindTexture(GL_TEXTURE_3D,atlas.texture);glBindSampler(units[0],0);
            glUniform1i(cellsLocation,units[0]);
            if(second!=null) {
                glActiveTexture(GL_TEXTURE0+units[1]);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas.shadowTexture);glBindSampler(units[1],0);
                glUniform1i(shadowLocation,units[1]);
            }
            uniforms.run();
            // Publish count only after both complete uploads and all source metadata are bound.
            glUniform1i(count,regions.size());
        } catch(RuntimeException | Error failure) {
            if(first!=null) first.restore();if(second!=null) second.restore();
            glUniform1i(count,0);throw failure;
        } finally { glActiveTexture(active); }
        UnitBinding savedFirst=first,savedSecond=second;
        return ()->{
            int current=glGetInteger(GL_ACTIVE_TEXTURE);
            try { savedFirst.restore();if(savedSecond!=null) savedSecond.restore(); }
            finally { glActiveTexture(current); }
        };
    }

    private record UnitBinding(int unit,int target,int texture,int sampler) {
        static UnitBinding capture(int unit,int target,int binding) {
            glActiveTexture(GL_TEXTURE0+unit);
            return new UnitBinding(unit,target,glGetInteger(binding),glGetInteger(GL_SAMPLER_BINDING));
        }
        void restore() { glActiveTexture(GL_TEXTURE0+unit);glBindTexture(target,texture);glBindSampler(unit,sampler); }
    }

    private static void uniforms(int program,List<PortalShaderLighting.Region> regions,Vec3 camera) {
        for(int i=0;i<regions.size();i++) {
            var r=regions.get(i);var a=r.aperture();var min=r.min();
            vector(program,"ipSunOrigin",i,new Vec3(min.x()-camera.x,min.y()-camera.y,min.z()-camera.z));
            vector(program,"ipSunPlane",i,a.center().subtract(camera));
            vector(program,"ipSunInward",i,a.inward());vector(program,"ipSunU",i,a.u());vector(program,"ipSunV",i,a.v());
            glUniform2f(location(program,"ipSunHalfSize",i),(float)(a.width()*.5),(float)(a.height()*.5));
            vector(program,"ipSunDirection",i,r.direction());
            // These are columns of the rigid transform, not row vectors.
            vector(program,"ipSunToSourceX",i,a.toSourceX());vector(program,"ipSunToSourceY",i,a.toSourceY());vector(program,"ipSunToSourceZ",i,a.toSourceZ());
            ClientLevel source=a.source();
            vector(program,"ipSunSourceSkyColor",i,source.getSkyColor(a.sourceCenter(),0));
            glUniform1f(location(program,"ipSunSourceSunAngle",i),r.sourceSunAngle());
            glUniform1i(location(program,"ipSunSourceWorldTime",i),(int)Math.floorMod(source.getDayTime(),24000L));
            glUniform1f(location(program,"ipSunSourceRain",i),source.getRainLevel(0));
            glUniform1f(location(program,"ipSunSourceMoonPhase",i),source.getMoonPhase());
            // Known loaded source only. A missing biome must not request a chunk or invent local Nether weather.
            BlockPos pos=BlockPos.containing(a.sourceCenter());
            Vec3 weather=Vec3.ZERO;
            if(source.hasChunkAt(pos)) weather=switch(source.getBiome(pos).value().getPrecipitationAt(pos)) {
                case RAIN -> new Vec3(1,0,0);
                case SNOW -> new Vec3(0,1,0);
                case NONE -> new Vec3(0,0,1);
            };
            vector(program,"ipSunSourceWeather",i,weather);
        }
    }

    private static int location(int program,String name,int slot) { return glGetUniformLocation(program,name+"["+slot+"]"); }
    private static void vector(int program,String name,int slot,Vec3 value) {
        glUniform3f(location(program,name,slot),(float)value.x,(float)value.y,(float)value.z);
    }

    static int[] unusedUnits(int program) {
        return unusedUnits(program,2);
    }

    private static int[] unusedUnits(int program,int required) {
        Set<Integer> occupied=new HashSet<>();
        var size=BufferUtils.createIntBuffer(1);var type=BufferUtils.createIntBuffer(1);
        for(int i=0;i<glGetProgrami(program,GL_ACTIVE_UNIFORMS);i++) {
            String name=glGetActiveUniform(program,i,size,type);
            if(name.equals("ipSunAtlas") || name.equals("ipSunSourceShadow")) continue;
            if(isSampler(type.get(0))) for(int j=0;j<size.get(0);j++) {
                String element=size.get(0)>1?name.replace("[0]","["+j+"]"):name;
                int location=glGetUniformLocation(program,element);
                if(location>=0) occupied.add(glGetUniformi(program,location));
            }
        }
        int[] result=new int[required];int count=0;
        for(int unit=glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS)-1;unit>=0;unit--)
            if(!occupied.contains(unit)) { result[count++]=unit;if(count==required) return result; }
        return null;
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
                GL_SAMPLER_2D_MULTISAMPLE_ARRAY,GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                // Core 4.0 cube-array samplers can be active in a pack even though our own textures use GL 3.3.
                0x900C,0x900D,0x900E,0x900F -> true;
            default -> false;
        };
    }
}
