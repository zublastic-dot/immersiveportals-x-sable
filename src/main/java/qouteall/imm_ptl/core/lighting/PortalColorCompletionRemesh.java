package qouteall.imm_ptl.core.lighting;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Client-thread queue: retain visible meshes while native initial RGB replaces their contents. */
final class PortalColorCompletionRemesh<W,R,C> {
    static final int MAX_ATTEMPTS_PER_TICK = 256;
    record Section<W,R,C>(W world,R renderer,C chunk,int x,int y,int z) {
        boolean stillOwned(Object registeredRenderer,Object loadedChunk,boolean worldLoaded) {
            return worldLoaded && renderer==registeredRenderer && chunk==loadedChunk;
        }
    }
    private final LinkedHashMap<Key,Section<W,R,C>> pending = new LinkedHashMap<>();

    static boolean ownsCompletion(Object world,Object renderer,Object accessor,Object actualWorld,
                                  Object publishedAccessor,Object registeredRenderer,long generation,long expectedGeneration) {
        return world!=null && renderer!=null && accessor!=null && world==actualWorld
            && accessor==publishedAccessor && renderer==registeredRenderer
            && generation!=0 && generation==expectedGeneration;
    }

    /** Complete the exact native handoff even when the caller must retain native remeshing. */
    static boolean handoff(boolean admitted,Runnable complete,Runnable enqueue) {
        complete.run();
        if (!admitted) return false;
        enqueue.run();
        return true;
    }

    static <T> List<T> distinctOwners(List<? extends T> owned,List<? extends T> hosted) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<T,Boolean>());
        var result=new ArrayList<T>();
        for (var group:List.of(owned,hosted)) for (T owner:group) if (seen.add(owner)) result.add(owner);
        return result;
    }

    void enqueue(W world,R renderer,C chunk,int x,int y,int z) {
        if (world == null || renderer == null || chunk == null) throw new IllegalArgumentException("Missing remesh owner");
        // A reloaded chunk replaces the old token without adding duplicate work at that position.
        pending.put(new Key(world,renderer,x,y,z),new Section<>(world,renderer,chunk,x,y,z));
    }

    void enqueueSections(W world,R renderer,C chunk,int x,int z,int minSection,int count,
                         java.util.function.IntPredicate needsMesh) {
        if (count < 0) throw new IllegalArgumentException("Negative section count");
        Math.addExact(minSection,count);
        for (int index=0;index<count;index++) if (needsMesh.test(index)) {
            enqueue(world,renderer,chunk,x,minSection+index,z);
        }
    }

    /** A false result means an in-flight first build must be retried after it uploads. */
    int drain(int budget,Predicate<Section<W,R,C>> finished) {
        if (budget < 0) throw new IllegalArgumentException("Negative remesh budget");
        int attempts=Math.min(budget,pending.size());
        for (int i=0;i<attempts;i++) {
            var iterator=pending.entrySet().iterator();
            var entry=iterator.next();
            Key key=entry.getKey(); var section=entry.getValue();
            iterator.remove();
            boolean done=false;
            try { done=finished.test(section); }
            finally { if (!done) pending.putIfAbsent(key,section); }
        }
        return attempts;
    }

    int size() { return pending.size(); }
    void clear() { pending.clear(); }

    private static final class Key {
        private final Object world,renderer;
        private final int x,y,z;
        Key(Object world,Object renderer,int x,int y,int z) {
            this.world=world; this.renderer=renderer; this.x=x; this.y=y; this.z=z;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && world==key.world && renderer==key.renderer
                && x==key.x && y==key.y && z==key.z;
        }
        @Override public int hashCode() {
            int hash=31*System.identityHashCode(world)+System.identityHashCode(renderer);
            return 31*(31*(31*hash+x)+y)+z;
        }
    }
}
