package qouteall.imm_ptl.core.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PortalLightSamplesTest {
    @Test void absentOrEmptyPropagationNeverInventsHeldLight(){
        assertEquals(0,PortalLightSamples.block((DataLayer)null,new BlockPos(0,0,0)));
        assertEquals(0,PortalLightSamples.block(new DataLayer(),new BlockPos(0,0,0)));
    }
    @Test void negativeCoordinatesUseLocalSectionIndices(){
        var data=new DataLayer();data.set(15,14,13,11);
        assertEquals(11,PortalLightSamples.block(data,new BlockPos(-1,-2,-3)));
        assertEquals(0,PortalLightSamples.block(data,new BlockPos(-2,-2,-3)));
    }
    @Test void sourceRemovalIsVisibleWithoutASecondCachedRead(){
        var data=new DataLayer();var pos=new BlockPos(21,37,53);data.set(5,5,5,14);
        assertEquals(14,PortalLightSamples.block(data,pos));data.set(5,5,5,0);
        assertEquals(0,PortalLightSamples.block(data,pos));
    }
    @Test void populatedSectionIsCopiedOncePerPassAndRemovalIsFreshInTheNextPass(){
        Object world=new Object();byte[] storage=new byte[2048];Arrays.fill(storage,(byte)0xbb);
        var copies=new AtomicInteger();
        java.util.function.BiFunction<Object,net.minecraft.core.SectionPos,DataLayer> read=(w,s)->{
            copies.incrementAndGet();return new DataLayer(storage.clone());
        };
        var pass=new PortalLightSamples.Pass<>(read);
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
            assertEquals(11,pass.block(world,new BlockPos(x,y,z)));
        assertEquals(1,copies.get(),"4096 cell reads need one 2048-byte section copy");
        Arrays.fill(storage,(byte)0);
        assertEquals(11,pass.block(world,BlockPos.ZERO),"One pass retains its captured section");
        assertEquals(0,new PortalLightSamples.Pass<>(read).block(world,BlockPos.ZERO));
        assertEquals(2,copies.get(),"No raw-light cache may survive into the next update pass");
    }
    @Test void missingSectionsAreCachedWithinPassButRetriedInTheNextPass(){
        Object world=new Object();var reads=new AtomicInteger();
        java.util.function.BiFunction<Object,net.minecraft.core.SectionPos,DataLayer> read=(w,s)->{reads.incrementAndGet();return null;};
        var pass=new PortalLightSamples.Pass<>(read);
        for(int x=0;x<16;x++) assertEquals(0,pass.block(world,new BlockPos(x,0,0)));
        assertEquals(1,reads.get());
        assertEquals(0,new PortalLightSamples.Pass<>(read).block(world,BlockPos.ZERO));assertEquals(2,reads.get());
    }
    @Test void equalWorldNamesDoNotShareSectionCopies(){
        record World(String name) {}
        World first=new World("same-dimension"),second=new World("same-dimension");assertEquals(first,second);
        var reads=new AtomicInteger();
        var pass=new PortalLightSamples.Pass<World>((world,section)->{
            reads.incrementAndGet();var layer=new DataLayer();layer.set(15,14,13,world==first?4:12);return layer;
        });
        BlockPos pos=new BlockPos(-1,-2,-3);
        assertEquals(4,pass.block(first,pos));assertEquals(12,pass.block(second,pos));assertEquals(4,pass.block(first,pos));
        assertEquals(2,reads.get());
    }
    @Test void boundedPassCacheStillReadsCorrectLightAfterSectionEviction(){
        Object world=new Object();var reads=new AtomicInteger();
        var pass=new PortalLightSamples.Pass<Object>((w,section)->{
            reads.incrementAndGet();var layer=new DataLayer();layer.set(0,0,0,7);return layer;
        });
        for(int i=0;i<1025;i++) assertEquals(7,pass.block(world,new BlockPos(i*16,0,0)));
        assertEquals(7,pass.block(world,BlockPos.ZERO));assertEquals(1026,reads.get());
    }
}
