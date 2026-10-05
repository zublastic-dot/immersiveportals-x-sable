package qouteall.imm_ptl.core.lighting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

/** Exact-world, demand-driven native block RGB. World reads are confined to {@link #advance}. */
final class PortalNativeColorCache<W> {
    static final int MAX_FIELDS = 128, MAX_READS = 8192, MAX_STEPS = 65536, MAX_REBUILDS = 8;
    static final long MAX_NANOS = 2_000_000;
    // One-cell interpolation border plus the full native propagation radius: 46^3 captured cells.
    private static final int SIDE = 18;
    static final class Section<W> {
        final W world;
        final int x, y, z;
        Section(W world, int x, int y, int z) { this.world = world; this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object value) {
            return value instanceof Section<?> other && world == other.world && x == other.x && y == other.y && z == other.z;
        }
        @Override public int hashCode() { return ((System.identityHashCode(world)*31+x)*31+y)*31+z; }
        Pos minimum() { return new Pos(x * 16 - 1, y * 16 - 1, z * 16 - 1); }
        boolean affected(Pos pos) {
            Pos min = minimum(); int radius = PortalColoredLightField.RADIUS;
            return pos.x() >= min.x()-radius && pos.x() < min.x()+SIDE+radius
                && pos.y() >= min.y()-radius && pos.y() < min.y()+SIDE+radius
                && pos.z() >= min.z()-radius && pos.z() < min.z()+SIDE+radius;
        }
    }
    private static final class Snapshot {
        final Pos min;
        final int[] rgb;
        final int maximum;
        Snapshot(Pos min, PortalColoredLightField.Job job) {
            this.min = min; rgb = new int[SIDE*SIDE*SIDE]; int peak = 0;
            for (int z=0; z<SIDE; z++) for (int y=0; y<SIDE; y++) for (int x=0; x<SIDE; x++) {
                var color = job.at(min.add(x,y,z));
                int value = color.red()*17 << 16 | color.green()*17 << 8 | color.blue()*17;
                rgb[index(x,y,z)] = value; peak = maximum(peak, value);
            }
            maximum = peak;
        }
        int sample(double x, double y, double z) {
            double cx=x-.5-min.x(), cy=y-.5-min.y(), cz=z-.5-min.z();
            int bx=(int)Math.floor(cx), by=(int)Math.floor(cy), bz=(int)Math.floor(cz);
            double fx=cx-bx, fy=cy-by, fz=cz-bz, red=0, green=0, blue=0;
            for (int dx=0; dx<2; dx++) for (int dy=0; dy<2; dy++) for (int dz=0; dz<2; dz++) {
                int value=rgb[index(bx+dx,by+dy,bz+dz)];
                double weight=(dx==0 ? 1-fx : fx)*(dy==0 ? 1-fy : fy)*(dz==0 ? 1-fz : fz);
                red+=(value>>>16 & 255)*weight; green+=(value>>>8 & 255)*weight; blue+=(value & 255)*weight;
            }
            return (int)Math.round(red)<<16 | (int)Math.round(green)<<8 | (int)Math.round(blue);
        }
        private static int index(int x,int y,int z) { return (z*SIDE+y)*SIDE+x; }
    }
    private static final class Entry {
        boolean dirty = true;
        long order;
        String capture = "queued";
    }
    private final Function<W, PortalColoredLightField.Reader> readers;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Section<W>, Boolean> requests = new ConcurrentHashMap<>();
    private final LinkedHashMap<Section<W>, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashSet<Section<W>> dirty = new LinkedHashSet<>();
    private volatile Map<Section<W>, Snapshot> published = Map.of();
    private volatile List<W> allowed = List.of();
    private Section<W> working;
    private PortalColoredLightField.Job job;
    private long serial, lastTick=Long.MIN_VALUE;
    long completed, elapsed;
    int reads, steps, rebuilt;

    PortalNativeColorCache(Function<W, PortalColoredLightField.Reader> readers, LongSupplier clock) {
        this.readers=readers; this.clock=clock;
    }
    boolean accepts(Object world) {
        for (W candidate:allowed) if (candidate==world) return true;
        return false;
    }
    /** Worker-safe: this method only reads immutable publications and queues a bounded request. */
    Integer sample(W world, double x, double y, double z) {
        if (!accepts(world) || !finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)) return null;
        Section<W> section=new Section<>(world,(int)Math.floor(x)>>4,(int)Math.floor(y)>>4,(int)Math.floor(z)>>4);
        Snapshot snapshot=published.get(section);
        if (snapshot != null) return snapshot.sample(x,y,z);
        if (!requests.containsKey(section)) synchronized (requests) {
            if (accepts(world) && requests.size()<MAX_FIELDS) requests.put(section,Boolean.TRUE);
        }
        return null;
    }
    private static boolean finiteCoordinate(double coordinate) {
        return Double.isFinite(coordinate) && coordinate>=-30_000_000 && coordinate<=30_000_000;
    }
    /** Client-thread priority demand prevents a full worker queue from permanently missing the portal room. */
    void seed(Section<W> section,ToDoubleFunction<Section<W>> priority) {
        if (!accepts(section.world) || entries.containsKey(section)) return;
        synchronized (requests) {
            if (requests.containsKey(section)) return;
            if (requests.size()>=MAX_FIELDS) {
                Section<W> worst=requests.keySet().stream().max(Comparator.comparingDouble(priority)).orElse(null);
                if (worst==null || priority.applyAsDouble(section)>=priority.applyAsDouble(worst)) return;
                requests.remove(worst);
            }
            requests.put(section,Boolean.TRUE);
        }
    }
    /** Called on the client thread; an equal dimension key never admits a replaced level object. */
    void worlds(List<W> remoteWorlds) {
        if (allowed.size()==remoteWorlds.size()) {
            boolean same=true;
            for (int i=0;i<allowed.size();i++) if (allowed.get(i)!=remoteWorlds.get(i)) { same=false; break; }
            if (same) return;
        }
        allowed=List.copyOf(remoteWorlds);
        requests.keySet().removeIf(k -> !accepts(k.world));
        var next=new HashMap<>(published);
        for (var iterator=entries.keySet().iterator(); iterator.hasNext();) {
            Section<W> section=iterator.next();
            if (!accepts(section.world)) {
                iterator.remove();
                if (next.remove(section)!=null) queueRebuild(section);
                if (section.equals(working)) { job=null; working=null; }
            }
        }
        published=Map.copyOf(next);
    }
    void invalidate(W world, Pos changed) {
        invalidateWhere(k -> k.world==world && k.affected(changed));
    }
    void invalidateChunk(W world, int chunkX, int chunkZ) {
        invalidateWhere(k -> k.world==world && Math.abs(k.x-chunkX)<=2 && Math.abs(k.z-chunkZ)<=2);
    }
    void invalidateAll() { invalidateWhere(k -> true); }
    private void invalidateWhere(Predicate<Section<W>> affected) {
        var next=new HashMap<>(published);
        for (var item:entries.entrySet()) if (affected.test(item.getKey())) {
            item.getValue().dirty=true;
            if (next.remove(item.getKey())!=null) queueRebuild(item.getKey());
            if (item.getKey().equals(working)) { job=null; working=null; }
        }
        published=Map.copyOf(next);
    }
    /** One shared time/read/flood budget and at most one full propagation volume, irrespective of demand. */
    void advance(long tick, ToDoubleFunction<Section<W>> priority) {
        if (lastTick==tick) return;
        lastTick=tick; reads=steps=0; long started=clock.getAsLong();
        // Stable priorities keep near-aperture terrain ahead of distant terrain during initial capture.
        var pending=new ArrayList<>(requests.keySet());
        pending.sort(Comparator.comparingDouble(priority));
        for (Section<W> section:pending) {
            // Reserve room for invalidating every current field. Saturation cannot create an
            // unbounded tail of cleanup notifications while initial Sodium builds are unready.
            if (dirty.size()>=MAX_FIELDS*27) break;
            requests.remove(section);
            if (!accepts(section.world) || entries.containsKey(section)) continue;
            if (entries.size()>=MAX_FIELDS) {
                Section<W> victim=entries.keySet().stream().filter(k -> !k.equals(working))
                    .max(Comparator.comparingDouble(priority)).orElse(null);
                // Keep the nearest bounded set stable. FIFO eviction here would cause endless
                // eviction -> mesh rebuild -> request cycles in a large remote landscape.
                if (victim==null || priority.applyAsDouble(section)>=priority.applyAsDouble(victim)) continue;
                entries.remove(victim);
                var next=new HashMap<>(published);
                if (next.remove(victim)!=null) queueRebuild(victim);
                published=Map.copyOf(next);
                if (victim.equals(working)) { job=null; working=null; }
            }
            Entry entry=new Entry(); entry.order=serial++; entries.put(section,entry);
        }
        if (job==null) {
            working=entries.entrySet().stream().filter(e -> e.getValue().dirty)
                .min(Comparator.<Map.Entry<Section<W>,Entry>>comparingDouble(e -> priority.applyAsDouble(e.getKey()))
                    .thenComparingLong(e -> e.getValue().order)).map(Map.Entry::getKey).orElse(null);
            if (working!=null) {
                var reader=readers.apply(working.world);
                if (reader!=null) {
                    Pos min=working.minimum(); job=new PortalColoredLightField.Job(reader,List.of(min,min.add(17,17,17)));
                } else {
                    entries.get(working).capture="native reader unavailable";
                    entries.get(working).dirty=false;
                }
            }
        }
        if (job!=null) {
            var work=job.advance(MAX_READS,MAX_STEPS,() -> clock.getAsLong()-started<MAX_NANOS);
            reads=work.reads(); steps=work.steps(); Entry entry=entries.get(working);
            entry.capture=job.captureStatus();
            if (work.complete()) {
                var next=new HashMap<>(published); next.put(working,new Snapshot(working.minimum(),job));
                // Publish before requesting a mesh: every worker observes a complete field.
                published=Map.copyOf(next); queueRebuild(working); entry.dirty=false; completed++;
                job=null; working=null;
            }
        }
        elapsed=clock.getAsLong()-started;
    }
    private void queueRebuild(Section<W> section) {
        // A face-normal vertex offset can query this field from any neighboring section.
        for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++)
            dirty.add(new Section<>(section.world,section.x+x,section.y+y,section.z+z));
    }
    void rebuild(Predicate<Section<W>> accepted) {
        rebuilt=0;
        int attempts=Math.min(MAX_REBUILDS,dirty.size());
        for (int i=0;i<attempts;i++) {
            Section<W> section=dirty.removeFirst();
            if (accepted.test(section)) rebuilt++; else dirty.add(section);
        }
    }
    void clear() {
        allowed=List.of(); requests.clear(); entries.clear(); published=Map.of(); dirty.clear();
        job=null; working=null; completed=0; lastTick=Long.MIN_VALUE; reads=steps=rebuilt=0; elapsed=0;
    }
    int ready() { return published.size(); }
    int pending() { return requests.size()+(int)entries.values().stream().filter(e -> e.dirty).count(); }
    int size() { return entries.size(); }
    int pendingRebuilds() { return dirty.size(); }
    void discardUnloadedWorlds(List<W> loaded) {
        dirty.removeIf(k -> loaded.stream().noneMatch(world -> world==k.world));
    }
    List<Map<String,Object>> describe(Function<W,String> name) {
        return entries.entrySet().stream().map(e -> {
            var k=e.getKey(); Snapshot s=published.get(k);
            return Map.<String,Object>of("world",name.apply(k.world),"section",List.of(k.x,k.y,k.z),
                "ready",s!=null,"rgb8Max",s==null ? 0 : s.maximum,"capture",e.getValue().capture);
        }).toList();
    }
    private static int maximum(int a,int b) {
        return Math.max(a>>>16 & 255,b>>>16 & 255)<<16 | Math.max(a>>>8 & 255,b>>>8 & 255)<<8 | Math.max(a & 255,b & 255);
    }
}
