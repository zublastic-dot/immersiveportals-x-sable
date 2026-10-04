package qouteall.imm_ptl.core.lighting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalLightCacheTraceTest {
    private static PortalLightCacheTrace.Face face(int id) {
        return new PortalLightCacheTrace.Face(new UUID(0,id),"mod:receiver",1,0,0);
    }
    private static PortalLightCacheTrace.Sample sample(String source,double... changes) {
        double[] values=new double[26]; values[0]=16; values[12]=4; values[13]=4;
        if(changes.length>0) values[0]=changes[0];
        if(changes.length>1) values[3]=changes[1];
        return new PortalLightCacheTrace.Sample("mod:receiver@1",source,values);
    }
    @Test void exactSubPixelAndSignedZeroChangesArePreservedWithoutRoundingThemIntoMotion() {
        var trace=new PortalLightCacheTrace(4); Map<String,String> report=new LinkedHashMap<>();
        trace.observe(1,face(1),sample("mod:source@2"),false,k->true,report::put);
        trace.observe(1,face(1),sample("mod:source@2",Math.nextUp(16d),-0d),true,k->true,report::put);
        String text=report.values().iterator().next();
        assertTrue(text.contains("keyChanges=1"));
        assertTrue(text.contains("center.x:0x1.0p4->0x1.0000000000001p4"));
        assertTrue(text.contains("inward.x:0x0.0p0->-0x0.0p0"));
        assertTrue(text.contains("maxPositionDelta="+(Math.nextUp(16d)-16d)));
        assertTrue(text.contains("currentCacheMiss=true"));
    }
    @Test void RecordsMovementBetweenRateLimitedReportsAndDistinguishesWorldReplacement() {
        var trace=new PortalLightCacheTrace(4); Map<String,String> report=new LinkedHashMap<>();
        AtomicBoolean budget=new AtomicBoolean(false);
        trace.observe(1,face(1),sample("mod:source@2"),true,k->budget.get(),report::put);
        trace.observe(1,face(1),sample("mod:source@2",17),true,k->budget.get(),report::put);
        assertTrue(report.isEmpty());
        budget.set(true);
        trace.observe(1,face(1),sample("mod:source@3",17),true,k->budget.get(),report::put);
        String text=report.values().iterator().next();
        assertTrue(text.contains("observations=3")); assertTrue(text.contains("keyChanges=2"));
        assertTrue(text.contains("maxPositionDelta=1.0"));
        assertTrue(text.contains("source:mod:source@2->mod:source@3"));
    }
    @Test void UnchangedKeyMissesAreNotMislabelledAsMotionAndSnapshotsOwnTheirNumbers() {
        var trace=new PortalLightCacheTrace(4); Map<String,String> report=new LinkedHashMap<>();
        double[] input=new double[26];
        var first=new PortalLightCacheTrace.Sample("target@1","source@1",input);
        trace.observe(1,face(1),first,true,k->true,report::put);
        input[0]=50;
        trace.observe(1,face(1),new PortalLightCacheTrace.Sample("target@1","source@1",new double[26]),true,k->true,report::put);
        String text=report.values().iterator().next();
        assertTrue(text.contains("cacheMisses=2")); assertTrue(text.contains("keyChanges=0"));
        assertTrue(text.contains("lastDelta=none"));
    }
    @Test void NewCaptureDropsOldFacesAndMoreThanFourFacesCannotRetainOrConsumeBudget() {
        var trace=new PortalLightCacheTrace(4); Map<String,String> report=new LinkedHashMap<>();
        int[] reservations={0};
        for(int i=0;i<100;i++) trace.observe(1,face(i),sample("source"),true,k->{reservations[0]++;return true;},report::put);
        assertEquals(4,reservations[0]);assertEquals(4,report.size());
        report.clear();
        trace.observe(2,face(99),sample("source"),false,k->true,report::put);
        assertEquals(1,report.size());
        String text=report.values().iterator().next();
        assertTrue(text.contains("observations=1")); assertTrue(text.contains("keyChanges=0"));
        assertTrue(text.contains("cacheMisses=0"));
    }
}
