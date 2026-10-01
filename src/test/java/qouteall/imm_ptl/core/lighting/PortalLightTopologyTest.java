package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalLightTopologyTest {
    private Cell corridor(Pos p) { return p.x()>=0&&p.x()<12&&p.y()==0&&p.z()==0?Cell.OPEN:Cell.CLOSED; }
    private Topology topology() { return buildTopology(this::corridor,Set.of(new Pos(0,0,0)),new Pos(1,0,0),32); }

    @Test void repeatedLightPropagationDoesNotRebuildGeometry() {
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var topology=buildTopology(p->{reads.incrementAndGet();return corridor(p);},Set.of(new Pos(0,0,0)),new Pos(1,0,0),32);
        int initial=reads.get();
        for(int value=15;value>=0;value--) {
            var result=propagate(topology,Map.of(new Pos(0,0,0),new Light(value,15-value)));
            for(int x=0;x<12;x++) assertEquals(new Light(value-x,15-value-x),result.cells().get(new Pos(x,0,0)));
        }
        assertEquals(initial,reads.get());assertEquals(12,topology.cellCount());
    }
    @Test void emptyCurrentSeedSetIsDarkWithoutLosingKnownGeometry() {
        var topology=topology();
        var bright=propagate(topology,Map.of(new Pos(0,0,0),new Light(15,15)));
        var dark=propagate(topology,Map.of());
        assertEquals(bright.cells().keySet(),dark.cells().keySet());
        assertSame(bright.replacement(),dark.replacement());
        assertTrue(dark.cells().values().stream().allMatch(l->l.equals(new Light(0,0))));
    }
    @Test void arbitraryInteriorSeedsCannotBypassTheAperture() {
        var result=propagate(topology(),Map.of(new Pos(5,0,0),new Light(15,15),new Pos(50,0,0),new Light(15,15)));
        assertTrue(result.cells().values().stream().allMatch(l->l.equals(new Light(0,0))));
    }
    @Test void simultaneousChannelsAndMultipleSeedsUseStrongestCurrentReachableSource() {
        var aperture=Set.of(new Pos(0,0,0),new Pos(0,1,0));
        var topology=buildTopology(p->p.x()>=0&&p.x()<8&&p.y()>=0&&p.y()<2&&p.z()==0?Cell.OPEN:Cell.CLOSED,
            aperture,new Pos(1,0,0),32);
        var result=propagate(topology,Map.of(new Pos(0,0,0),new Light(15,0),new Pos(0,1,0),new Light(0,15)));
        assertEquals(new Light(11,10),result.cells().get(new Pos(4,0,0)));
        assertEquals(new Light(10,11),result.cells().get(new Pos(4,1,0)));
        var removed=propagate(topology,Map.of(new Pos(0,0,0),new Light(15,0)));
        assertEquals(new Light(10,0),removed.cells().get(new Pos(4,1,0)));
    }
}
