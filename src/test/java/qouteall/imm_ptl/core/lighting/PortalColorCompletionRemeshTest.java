package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PortalColorCompletionRemeshTest {
    @Test void completionRequiresExactOwnerAndCurrentNativeGeneration() {
        record Named(String value) {}
        Object world=new Named("world"), renderer=new Named("renderer"), accessor=new Named("accessor"),engine=new Object();
        var warmup=new PortalColorWarmup<Object>(); warmup.begin(world,engine);
        long generation=warmup.token(world,engine);
        assertTrue(owns(world,renderer,accessor,world,accessor,renderer,generation,warmup.token(world,engine)));
        assertFalse(owns(world,renderer,accessor,new Named("world"),accessor,renderer,generation,generation));
        assertFalse(owns(world,renderer,accessor,world,new Named("accessor"),renderer,generation,generation));
        assertFalse(owns(world,renderer,accessor,world,accessor,new Named("renderer"),generation,generation));
        assertFalse(owns(world,renderer,accessor,world,accessor,renderer,generation,warmup.token(world,new Object())));
        warmup.begin(world,engine);
        assertFalse(owns(world,renderer,accessor,world,accessor,renderer,generation,warmup.token(world,engine)));
        assertFalse(owns(world,renderer,accessor,world,accessor,renderer,0,0));
        assertFalse(owns(null,renderer,accessor,null,accessor,renderer,1,1));
    }

    @Test void nativeCompletionRunsOnceBeforeQueueOrOriginalFallback() {
        var events=new ArrayList<String>();
        assertTrue(PortalColorCompletionRemesh.handoff(true,()->events.add("complete"),()->events.add("enqueue")));
        assertEquals(List.of("complete","enqueue"),events);
        events.clear();
        if (!PortalColorCompletionRemesh.handoff(false,()->events.add("complete"),()->fail("Must not enqueue")))
            events.add("original");
        assertEquals(List.of("complete","original"),events);
    }

    @Test void ordinaryAndHostedPlotCallbacksAreRetainedOncePerExactOwner() {
        record Plot(String name) {}
        var ordinary=new Plot("same"); var hosted=new Plot("same");
        var owners=PortalColorCompletionRemesh.distinctOwners(List.of(ordinary),List.of(ordinary,hosted));
        assertEquals(2,owners.size()); assertSame(ordinary,owners.getFirst()); assertSame(hosted,owners.getLast());
    }

    @Test void loadedChunkBoundsIncludeAllLegalHeightsWithoutVanillaOrCameraClamping() {
        var queue=new PortalColorCompletionRemesh<Object,Object,Object>();
        Object world=new Object(),renderer=new Object(),chunk=new Object();
        queue.enqueueSections(world,renderer,chunk,-9,8,-127,254,index -> true);
        var ys=new ArrayList<Integer>();
        queue.drain(300,section -> { ys.add(section.y()); assertSame(chunk,section.chunk()); return true; });
        assertEquals(254,ys.size()); assertEquals(-127,ys.getFirst()); assertEquals(126,ys.getLast());
        queue.enqueueSections(world,renderer,chunk,1,2,-6,38,index -> index==0 || index==37);
        ys.clear(); queue.drain(300,section -> { ys.add(section.y()); return true; });
        assertEquals(List.of(-6,31),ys);
        assertThrows(ArithmeticException.class,()->queue.enqueueSections(world,renderer,chunk,0,0,Integer.MAX_VALUE,1,i->true));
        assertEquals(0,queue.size());
    }

    @Test void dedupUsesWorldRendererIdentityAndReplacesReloadedChunkToken() {
        record Named(String value) {}
        var queue=new PortalColorCompletionRemesh<Object,Object,Object>();
        Object world=new Named("same"),otherWorld=new Named("same"),renderer=new Named("same"),otherRenderer=new Named("same");
        Object oldChunk=new Object(),newChunk=new Object();
        queue.enqueue(world,renderer,oldChunk,1,2,3);
        queue.enqueue(world,renderer,newChunk,1,2,3);
        queue.enqueue(otherWorld,renderer,oldChunk,1,2,3);
        queue.enqueue(world,otherRenderer,oldChunk,1,2,3);
        assertEquals(3,queue.size());
        var seen=new ArrayList<PortalColorCompletionRemesh.Section<Object,Object,Object>>();
        queue.drain(3,section -> { seen.add(section); return true; });
        assertSame(newChunk,seen.getFirst().chunk()); assertEquals(0,queue.size());
    }

    @Test void retryRotatesAndBudgetNeverDropsRunningFirstBuild() {
        var queue=new PortalColorCompletionRemesh<Object,Object,Object>();
        Object world=new Object(),renderer=new Object(),chunk=new Object();
        for (int y=0;y<300;y++) queue.enqueue(world,renderer,chunk,0,y,0);
        var attempts=new AtomicInteger();
        assertEquals(256,queue.drain(PortalColorCompletionRemesh.MAX_ATTEMPTS_PER_TICK,s -> { attempts.incrementAndGet(); return false; }));
        assertEquals(256,attempts.get()); assertEquals(300,queue.size());
        var first=new ArrayList<Integer>();
        queue.drain(1,s -> { first.add(s.y()); return true; });
        assertEquals(List.of(256),first);
        assertEquals(256,queue.drain(256,s -> true)); assertEquals(43,queue.size());
        assertEquals(43,queue.drain(256,s -> true)); assertEquals(0,queue.size());
    }

    @Test void staleOwnerOrUnloadedChunkDropsWithoutSchedulingItsReplacement() {
        var queue=new PortalColorCompletionRemesh<Object,Object,Object>();
        Object world=new Object(),renderer=new Object(),replacementRenderer=new Object(),chunk=new Object();
        queue.enqueue(world,renderer,chunk,1,2,3);
        var scheduled=new AtomicInteger();
        queue.drain(1,s -> {
            assertFalse(s.stillOwned(replacementRenderer,chunk,true));
            assertFalse(s.stillOwned(renderer,new Object(),true));
            assertFalse(s.stillOwned(renderer,null,true));
            assertFalse(s.stillOwned(renderer,chunk,false));
            assertTrue(s.stillOwned(renderer,chunk,true));
            if (!s.stillOwned(replacementRenderer,chunk,true)) return true;
            scheduled.incrementAndGet(); return true;
        });
        assertEquals(0,scheduled.get()); assertEquals(0,queue.size());
        queue.enqueue(world,renderer,chunk,1,2,3);
        assertThrows(IllegalStateException.class,()->queue.drain(1,s -> { throw new IllegalStateException("retry safely"); }));
        assertEquals(1,queue.size());
        queue.clear(); assertEquals(0,queue.size());
    }

    private static boolean owns(Object w,Object r,Object a,Object actual,Object published,Object registered,long token,long expected) {
        return PortalColorCompletionRemesh.ownsCompletion(w,r,a,actual,published,registered,token,expected);
    }
}
