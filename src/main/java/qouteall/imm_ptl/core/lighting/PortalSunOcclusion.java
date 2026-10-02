package qouteall.imm_ptl.core.lighting;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Objects;

import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

/** Bounded observations owned by one actual source world and aperture, never a dimension name. */
public final class PortalSunOcclusion {
    public static final int DEFAULT_SECTIONS=512;
    private static final int RAY_STEPS=1024;
    private static final class Section {
        final long[] known=new long[64], open=new long[64], attempted=new long[64];
        long pass=Long.MIN_VALUE;
    }
    private final Object owner;
    private final int capacity;
    private final Long2ObjectLinkedOpenHashMap<Section> sections=new Long2ObjectLinkedOpenHashMap<>();
    private long pass, reads, evictions;
    private long lastKey;
    private Section lastSection;

    public PortalSunOcclusion(Object owner,int capacity) {
        this.owner=Objects.requireNonNull(owner);
        if(capacity<1 || capacity>4096) throw new IllegalArgumentException("Invalid source shadow cache bound");
        this.capacity=capacity;
    }

    public void beginPass() { pass++;reads=0; }
    public long worldReads() { return reads; }
    public long evictions() { return evictions; }
    public int sectionCount() { return sections.size(); }

    /** Unknown reads are deduplicated only for this pass; prior known cells survive an unload. */
    public Cell sample(Object world,World reader,Pos pos) {
        if(world!=owner) return Cell.UNKNOWN;
        long key=SectionPos.asLong(pos.x()>>4,pos.y()>>4,pos.z()>>4);
        Section section;
        if(lastSection!=null && lastKey==key) section=lastSection;
        else {
            section=sections.getAndMoveToLast(key);
            if(section==null) {
                if(sections.size()==capacity) { sections.removeFirst();evictions++; }
                section=new Section();sections.putAndMoveToLast(key,section);
            }
            lastKey=key;lastSection=section;
        }
        int index=((pos.y()&15)<<8)|((pos.z()&15)<<4)|(pos.x()&15), word=index>>>6;
        long bit=1L<<(index&63);
        if((section.known[word]&bit)!=0) return (section.open[word]&bit)!=0?Cell.OPEN:Cell.CLOSED;
        if(section.pass!=pass) { Arrays.fill(section.attempted,0);section.pass=pass; }
        if((section.attempted[word]&bit)!=0) return Cell.UNKNOWN;
        section.attempted[word]|=bit;
        reads++;
        Cell observed=reader.cell(pos);
        if(observed==null || observed==Cell.UNKNOWN) return Cell.UNKNOWN;
        section.known[word]|=bit;
        if(observed==Cell.OPEN) section.open[word]|=bit;
        else section.open[word]&=~bit;
        return observed;
    }

    /** A real block mutation invalidates its observation, including an earlier unknown read. */
    public boolean invalidate(Pos pos) {
        long key=SectionPos.asLong(pos.x()>>4,pos.y()>>4,pos.z()>>4);
        Section section=sections.get(key);
        if(section==null) return false;
        int index=((pos.y()&15)<<8)|((pos.z()&15)<<4)|(pos.x()&15),word=index>>>6;
        long bit=1L<<(index&63);
        boolean observed=((section.known[word]|section.attempted[word])&bit)!=0;
        section.known[word]&=~bit;section.open[word]&=~bit;section.attempted[word]&=~bit;
        lastSection=null;
        return observed;
    }

    /** A newly received chunk supersedes every old observation in that column; unload does not. */
    public boolean invalidateChunk(int chunkX,int chunkZ) {
        boolean changed=false;
        var iterator=sections.long2ObjectEntrySet().iterator();
        while(iterator.hasNext()) {
            long key=iterator.next().getLongKey();
            if(SectionPos.x(key)==chunkX && SectionPos.z(key)==chunkZ) { iterator.remove();changed=true; }
        }
        if(changed) lastSection=null;
        return changed;
    }

    /** Rays share retained geometry while their current mask remains an immutable fresh publication. */
    public byte[] mask(Object world,World reader,Vec3 center,Vec3 normal,Vec3 u,Vec3 v,
                       double width,double height,Vec3 towardSun,int ceiling,int edge) {
        if(edge<1 || edge>32) throw new IllegalArgumentException("Invalid source shadow mask edge");
        beginPass();
        byte[] result=new byte[edge*edge];
        if(world!=owner || towardSun.y<=1e-5 || towardSun.dot(normal)<=1e-5) return result;
        World cached=pos->sample(world,reader,pos);
        for(int y=0;y<edge;y++) for(int x=0;x<edge;x++) {
            Vec3 from=center.add(u.scale(((x+.5)/edge-.5)*width))
                .add(v.scale(((y+.5)/edge-.5)*height)).add(normal.scale(.002));
            if(PortalSunGeometry.clearToSky(cached,from,towardSun,ceiling,RAY_STEPS)) result[y*edge+x]=(byte)255;
        }
        return result;
    }
}
