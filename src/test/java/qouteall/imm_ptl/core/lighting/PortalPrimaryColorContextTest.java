package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.concurrent.FutureTask;
import static org.junit.jupiter.api.Assertions.*;

class PortalPrimaryColorContextTest {
    @Test void temporaryRenderWorldCannotReplacePublishedPlayerAccessor() {
        Object playerWorld=new Object(),portalWorld=new Object(),playerAccessor=new Object();
        var context=new PortalPrimaryColorContext<Object,Object>();
        context.publish(playerWorld,playerAccessor);
        assertSame(playerAccessor,context.select(playerWorld));
        assertNull(context.select(portalWorld));
        assertSame(playerAccessor,context.select(playerWorld));
    }
    @Test void teleportAtSameCoordinatesOrSameDimensionKeyRevokesOldNativeStorageIdentity() {
        record World(String key) {}
        World before=new World("custom:destination"),after=new World("custom:destination");
        var context=new PortalPrimaryColorContext<World,String>();
        context.publish(before,"before world and renderer");
        assertNull(context.select(after),"A client tick must publish the replacement pair before native work resumes");
        context.publish(after,"after world and renderer");
        assertEquals("after world and renderer",context.select(after)); assertNull(context.select(before));
        context.clear(); assertNull(context.select(after));
    }
    @Test void workerReceivesWholePublishedPairAndRendererReplacement() throws Exception {
        Object world=new Object(); var context=new PortalPrimaryColorContext<Object,String>();
        context.publish(world,"first renderer");
        var sample=new FutureTask<String>(() -> context.select(world)); new Thread(sample).start();
        assertEquals("first renderer",sample.get());
        context.publish(world,"reloaded renderer");
        sample=new FutureTask<>(() -> context.select(world)); new Thread(sample).start();
        assertEquals("reloaded renderer",sample.get());
    }
}
