package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalColorWarmupTest {
    @Test void handoffRejectsOtherWorldOtherEngineAndEarlierResetGeneration() {
        record World(String name) {}
        World first=new World("custom:world"),sameName=new World("custom:world");
        Object engine=new Object(),otherEngine=new Object();
        var bridge=new PortalColorWarmup<World>(); bridge.begin(first,engine);
        long token=bridge.token(first,engine); assertTrue(token>0);
        bridge.complete(sameName,engine,token); assertTrue(bridge.applies(first));
        bridge.complete(first,otherEngine,token); assertTrue(bridge.applies(first));
        bridge.begin(first,engine); assertTrue(bridge.token(first,engine)>token);
        bridge.complete(first,engine,token); assertTrue(bridge.applies(first));
        bridge.complete(first,engine,bridge.token(first,engine)); assertFalse(bridge.applies(first));
    }
    @Test void crossingRearmsNewOwnerAfterResetSawPreviousPrimary() {
        Object oldWorld=new Object(),newWorld=new Object(),engine=new Object();
        var bridge=new PortalColorWarmup<Object>();
        bridge.begin(oldWorld,engine); bridge.begin(newWorld,engine);
        assertFalse(bridge.applies(oldWorld)); assertTrue(bridge.applies(newWorld));
        bridge.clear(); assertFalse(bridge.applies(newWorld)); assertEquals(0,bridge.generation());
    }
    @Test void nativeDynamicMaxPreservesHeldLightsWithoutMixingPartialStaticNativeColor() {
        int blueSnapshot=0x0011cc,heldRed=0xdd2200;
        assertEquals(0xdd22cc,PortalColorWarmup.combine(blueSnapshot,heldRed));
        assertEquals(blueSnapshot,PortalColorWarmup.combine(blueSnapshot,0));
        assertEquals(heldRed,PortalColorWarmup.combine(0,heldRed));
        assertEquals(0xffffff,PortalColorWarmup.combine(0xff00ff,0x00ff00));
    }
}
