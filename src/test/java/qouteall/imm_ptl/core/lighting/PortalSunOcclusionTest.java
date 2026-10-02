package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalSunOcclusionTest {
    private static final Vec3 NORMAL=new Vec3(1,0,0),U=new Vec3(0,1,0),V=new Vec3(0,0,1);

    @Test void knownOpenAndBlockedGeometrySurvivesSourceChunkUnload() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);
        Pos open=new Pos(0,64,0),blocked=new Pos(1,64,0);
        cache.beginPass();
        assertEquals(Cell.OPEN,cache.sample(source,p->Cell.OPEN,open));
        assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,blocked));
        assertEquals(2,cache.worldReads());
        cache.beginPass();
        World unloaded=p->{fail("Previously observed cells must not be queried after unload");return Cell.UNKNOWN;};
        assertEquals(Cell.OPEN,cache.sample(source,unloaded,open));
        assertEquals(Cell.CLOSED,cache.sample(source,unloaded,blocked));
        assertEquals(0,cache.worldReads());
    }

    @Test void unknownIsClosedAndDeduplicatedOnlyWithinItsCurrentPass() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);Pos p=new Pos(0,64,0);
        cache.beginPass();
        for(int i=0;i<100;i++) assertEquals(Cell.UNKNOWN,cache.sample(source,q->Cell.UNKNOWN,p));
        assertEquals(1,cache.worldReads());
        cache.beginPass();
        assertEquals(Cell.OPEN,cache.sample(source,q->Cell.OPEN,p));
        assertEquals(1,cache.worldReads(),"Unknown observations must be eligible for a later loaded read");
    }

    @Test void otherWorldIdentityNeverReadsOrReusesThisCache() {
        Object source=new Object(),other=new Object();var cache=new PortalSunOcclusion(source,8);Pos p=new Pos(0,64,0);
        cache.beginPass();cache.sample(source,q->Cell.OPEN,p);
        cache.beginPass();
        assertEquals(Cell.UNKNOWN,cache.sample(other,q->{fail();return Cell.OPEN;},p));
        assertEquals(0,cache.worldReads());
        assertEquals(Cell.OPEN,cache.sample(source,q->{fail();return Cell.UNKNOWN;},p));
    }

    @Test void blockInvalidationReplacesOnlyTheChangedObservation() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);
        Pos changed=new Pos(0,64,0),stable=new Pos(1,64,0);
        cache.beginPass();cache.sample(source,p->Cell.OPEN,changed);cache.sample(source,p->Cell.CLOSED,stable);
        assertTrue(cache.invalidate(changed));assertFalse(cache.invalidate(new Pos(2,64,0)));
        cache.beginPass();
        assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,changed));
        assertEquals(Cell.CLOSED,cache.sample(source,p->{fail();return Cell.OPEN;},stable));
        assertEquals(1,cache.worldReads());
    }

    @Test void chunkReloadInvalidatesItsEntireColumnWithoutDiscardingOtherChunks() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);
        Pos lower=new Pos(-1,0,-1),upper=new Pos(-1,300,-1),other=new Pos(0,0,-1);
        cache.beginPass();
        for(Pos p:new Pos[]{lower,upper,other}) cache.sample(source,q->Cell.OPEN,p);
        assertTrue(cache.invalidateChunk(-1,-1));assertFalse(cache.invalidateChunk(5,5));
        cache.beginPass();
        assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,lower));
        assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,upper));
        assertEquals(Cell.OPEN,cache.sample(source,p->{fail();return Cell.UNKNOWN;},other));
        assertEquals(2,cache.worldReads());
    }

    @Test void lruBoundPreservesRecentObservationsAndStillReadsLoadedGeometryAtCapacity() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,2);
        Pos a=new Pos(0,64,0),b=new Pos(16,64,0),c=new Pos(32,64,0);
        cache.beginPass();cache.sample(source,p->Cell.CLOSED,a);cache.sample(source,p->Cell.OPEN,b);
        cache.sample(source,p->{fail();return Cell.OPEN;},a); // A is most recently used.
        assertEquals(Cell.OPEN,cache.sample(source,p->Cell.OPEN,c));
        assertEquals(2,cache.sectionCount());assertEquals(1,cache.evictions());
        cache.beginPass();
        assertEquals(Cell.CLOSED,cache.sample(source,p->{fail();return Cell.UNKNOWN;},a));
        assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,b),"Loaded data remains usable after capacity eviction");
        assertEquals(2,cache.sectionCount());assertEquals(1,cache.worldReads());
        cache.beginPass();
        assertEquals(Cell.UNKNOWN,cache.sample(source,p->Cell.UNKNOWN,c),"Evicted, unloaded terrain must fail closed");
    }

    @Test void fullApertureMaskReusesObservedGeometryWithZeroRepeatWorldReads() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,PortalSunOcclusion.DEFAULT_SECTIONS);
        Vec3 center=new Vec3(.5,64.5,.5),sun=new Vec3(1,1,.2);
        byte[] first=cache.mask(source,p->Cell.OPEN,center,NORMAL,U,V,16,16,sun,128,32);
        long firstReads=cache.worldReads();
        assertTrue(firstReads>0);assertEquals(0,cache.evictions());
        for(byte value:first) assertEquals(255,Byte.toUnsignedInt(value));
        byte[] repeat=cache.mask(source,p->{fail("Repeated mask must use observed geometry");return Cell.UNKNOWN;},center,NORMAL,U,V,16,16,sun,128,32);
        assertArrayEquals(first,repeat);assertEquals(0,cache.worldReads());
        byte[] shifted=cache.mask(source,p->Cell.OPEN,center,NORMAL,U,V,16,16,new Vec3(1,1,.2001),128,32);
        long shiftedReads=cache.worldReads();
        assertArrayEquals(first,shifted);assertTrue(shiftedReads<firstReads);
        System.out.printf("Portal source shadow 32x32: initial=%d world reads, identical/unloaded=0, nearby sun=%d, sections=%d%n",
            firstReads,shiftedReads,cache.sectionCount());
    }

    @Test void actualMaskRespondsToRoofInsertionAndRemovalWithoutStaleClearRays() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);Set<Pos> roof=new HashSet<>();
        World world=p->roof.contains(p)?Cell.CLOSED:Cell.OPEN;
        Vec3 center=new Vec3(0,1.5,.5),sun=new Vec3(1,1,0);Pos blocker=new Pos(1,2,0);
        byte[] original=cache.mask(source,world,center,NORMAL,U,V,1,1,sun,4,1);
        assertEquals(255,Byte.toUnsignedInt(original[0]));
        roof.add(blocker);assertTrue(cache.invalidate(blocker));
        assertEquals(0,cache.mask(source,world,center,NORMAL,U,V,1,1,sun,4,1)[0]);
        assertEquals(1,cache.worldReads());
        roof.remove(blocker);assertTrue(cache.invalidate(blocker));
        assertEquals(255,Byte.toUnsignedInt(cache.mask(source,world,center,NORMAL,U,V,1,1,sun,4,1)[0]));
        assertEquals(1,cache.worldReads());assertEquals(255,Byte.toUnsignedInt(original[0]),"Old publications remain immutable");
    }

    @Test void unknownFrontFacingRaysStayDarkAndBackFacingSunDoesNoWorldReads() {
        Object source=new Object();var cache=new PortalSunOcclusion(source,8);
        var calls=new AtomicInteger();World unknown=p->{calls.incrementAndGet();return Cell.UNKNOWN;};
        byte[] dark=cache.mask(source,unknown,new Vec3(.5,1.5,.5),NORMAL,U,V,1,1,new Vec3(1,1,0),8,4);
        assertArrayEquals(new byte[16],dark);assertTrue(calls.get()>0);
        calls.set(0);
        assertArrayEquals(new byte[16],cache.mask(source,unknown,new Vec3(.5,1.5,.5),NORMAL,U,V,1,1,new Vec3(-1,1,0),8,4));
        assertEquals(0,calls.get());assertEquals(0,cache.worldReads());
    }

    @Test void boundsAreExplicitAndLargeNegativeCoordinatesDoNotAlias() {
        Object source=new Object();assertThrows(IllegalArgumentException.class,()->new PortalSunOcclusion(source,0));
        assertThrows(IllegalArgumentException.class,()->new PortalSunOcclusion(source,4097));
        var cache=new PortalSunOcclusion(source,8);Pos a=new Pos(-20_000_000,-63,20_000_000),b=new Pos(-19_999_984,-63,20_000_000);
        cache.beginPass();assertEquals(Cell.OPEN,cache.sample(source,p->Cell.OPEN,a));assertEquals(Cell.CLOSED,cache.sample(source,p->Cell.CLOSED,b));
        assertEquals(Cell.OPEN,cache.sample(source,p->{fail();return Cell.CLOSED;},a));
        assertThrows(IllegalArgumentException.class,()->cache.mask(source,p->Cell.OPEN,Vec3.ZERO,NORMAL,U,V,1,1,NORMAL,128,33));
    }
}
