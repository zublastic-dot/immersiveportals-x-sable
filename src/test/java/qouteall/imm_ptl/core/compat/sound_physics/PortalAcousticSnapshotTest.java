package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.compat.sound_physics.PortalAcousticSnapshot.*;

class PortalAcousticSnapshotTest {
    @BeforeAll static void bootstrap() { PortalBlockTestBootstrap.initialize(); }
    private static final Grid ROOT = new Grid(0,0,0,8,4,4);
    private static final Vec3 START = new Vec3(.5,1.5,1.5), END = new Vec3(7.5,1.5,1.5);

    private static Sample sample(BlockState state) {
        var fluid = state.getFluidState();
        return new Sample(state, fluid, state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()),
            fluid.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
    private static Sample air() { return sample(Blocks.AIR.defaultBlockState()); }
    private static Input input(String id, Frame frame, Grid selected, Grid domain, boolean root, Function<BlockPos,Sample> read) {
        return new Input(id, frame, selected, domain, root, read, -64,384);
    }
    private static Input root(Function<BlockPos,Sample> read) { return input("root",Frame.identity(),ROOT,ROOT,true,read); }
    private static Snapshot copy(List<Input> inputs) {
        Snapshot result = new CaptureJob(ROOT.box(),inputs,()->0).advance(1);
        assertNotNull(result); return result;
    }

    @Test void actualWoolMaterialSurvivesCaptureAndRayIsInPhysicalCoordinates() {
        Snapshot snapshot=copy(List.of(root(p->p.getX()==4?sample(Blocks.WHITE_WOOL.defaultBlockState()):air())));
        RayResult ray=snapshot.rayCast(START,END,null);
        assertEquals(Status.HIT,ray.status());assertEquals(new Vec3(4,1.5,1.5),ray.worldLocation());
        assertEquals(new Vec3(-1,0,0),ray.worldNormal());
        assertEquals(new BlockPos(4,1,1),ray.localHit().getBlockPos());
        assertSame(Blocks.WHITE_WOOL,ray.space().getBlockState(ray.localHit().getBlockPos()).getBlock());
        assertEquals("root",ray.space().acousticId());
    }

    @Test void MissingGeometryNeverBecomesAirOrAnAuthoritativeMiss() {
        Snapshot snapshot=copy(List.of(root(p->p.getX()==3?null:air())));
        RayResult ray=snapshot.rayCast(START,END,null);
        assertEquals(Status.UNKNOWN,ray.status());assertTrue(ray.reason().contains("unknown_cell"));
        assertEquals(0,new Vec3(3,1.5,1.5).distanceTo(ray.worldLocation()),1e-12);
        assertFalse(snapshot.spaces().getFirst().known(new BlockPos(3,1,1)));
        assertTrue(snapshot.spaces().getFirst().getBlockState(new BlockPos(3,1,1)).is(Blocks.BEDROCK));
        assertNull(snapshot.blockAt(new Vec3(3.5,1.5,1.5)));
    }

    @Test void KnownAirIsDistinctFromUnknownAndSnapshotBoundaryIsExplicit() {
        Snapshot snapshot=copy(List.of(root(p->air())));
        assertEquals(Status.MISS,snapshot.rayCast(START,END,null).status());
        assertNotNull(snapshot.blockAt(START));assertTrue(snapshot.covers(START,END));
        assertEquals(Status.UNKNOWN,snapshot.rayCast(START,new Vec3(8.5,1.5,1.5),null).status());
        assertNull(snapshot.blockAt(new Vec3(8,1,1)));
    }

    @Test void PublishedGeometryIsDetachedFromMutableReaderAndNeedsNoReaderOnAudioThread() throws Exception {
        Map<BlockPos,Sample> live=new HashMap<>();live.put(new BlockPos(4,1,1),sample(Blocks.STONE.defaultBlockState()));
        AtomicInteger reads=new AtomicInteger();
        Snapshot snapshot=copy(List.of(root(p->{reads.incrementAndGet();return live.getOrDefault(p,air());})));
        int completedReads=reads.get();live.clear();
        AtomicReference<RayResult> result=new AtomicReference<>();
        Thread audio=new Thread(()->result.set(snapshot.rayCast(START,END,null)));
        audio.start();audio.join();
        assertEquals(Status.HIT,result.get().status());assertEquals(completedReads,reads.get());
        assertThrows(UnsupportedOperationException.class,()->snapshot.spaces().clear());
    }

    @Test void IncrementalJobPublishesNothingUntilCompleteAndResumesWithoutReplayingReads() {
        AtomicLong clock=new AtomicLong();Set<BlockPos> sampled=new HashSet<>();
        CaptureJob job=new CaptureJob(ROOT.box(),List.of(root(p->{
            assertTrue(sampled.add(p),"Each cell must be captured exactly once per job");return air();
        })),clock::getAndIncrement);
        assertNull(job.advance(8));assertEquals(7,sampled.size());
        Snapshot result=null;
        for(int attempt=0;attempt<40&&result==null;attempt++)result=job.advance(8);
        assertNotNull(result);assertEquals(ROOT.volume(),sampled.size());
        assertSame(result,job.advance(8));assertEquals(ROOT.volume(),sampled.size());
    }

    @Test void CaptureOffOwnerThreadIsRejectedBeforeAnyLiveRead() throws Exception {
        AtomicInteger reads=new AtomicInteger();
        CaptureJob job=new CaptureJob(ROOT.box(),List.of(root(p->{reads.incrementAndGet();return air();})),()->0);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread other=new Thread(()->{try{job.advance(100);}catch(Throwable t){failure.set(t);}});
        other.start();other.join();
        assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(0,reads.get());
    }

    @Test void ZeroBudgetDoesNoReadsAndOldPartialCapturesCannotPublishAsFreshGeometry() {
        AtomicLong clock=new AtomicLong();AtomicInteger reads=new AtomicInteger();
        CaptureJob job=new CaptureJob(ROOT.box(),List.of(root(p->{reads.incrementAndGet();return air();})),clock::get);
        assertNull(job.advance(0));assertEquals(0,reads.get());
        clock.set(500_000_000L);
        Snapshot expired=job.advance(4_000_000L);
        assertEquals("capture_expired",expired.reason());assertEquals(0,reads.get());
    }

    @Test void CaptureCellAndSpaceBudgetsFailBeforeAnyReaderIsCalled() {
        AtomicInteger reads=new AtomicInteger();Function<BlockPos,Sample> reader=p->{reads.incrementAndGet();return air();};
        Grid large=new Grid(0,0,0,33,33,33);
        Snapshot oversized=new CaptureJob(large.box(),List.of(input("root",Frame.identity(),large,large,true,reader)),()->0).advance(1);
        assertEquals("cell_budget",oversized.reason());assertEquals(Status.UNKNOWN,oversized.rayCast(START,END,null).status());
        List<Input> tooMany=new ArrayList<>();for(int i=0;i<17;i++)tooMany.add(root(reader));
        assertEquals("space_budget",new CaptureJob(ROOT.box(),tooMany,()->0).advance(1).reason());
        assertEquals(0,reads.get());
    }

    @Test void RotatedTranslatedSableSpaceRetainsLocalBlockRefButTransformsHitAndNormal() {
        // Plot coordinates are far from the visible world. A 90-degree rotation maps local +Z
        // toward physical +X and local +X toward physical -Z.
        Vec3 plot=new Vec3(20_481_000,100,20_483_000);
        Frame frame=new Frame(plot,new Vec3(4,1,2),new Vec3(0,0,-1),new Vec3(0,1,0),new Vec3(1,0,0));
        Grid grid=new Grid((int)plot.x,(int)plot.y,(int)plot.z,(int)plot.x+1,(int)plot.y+1,(int)plot.z+1);
        Snapshot snapshot=copy(List.of(root(p->air()),input("sable:fixture",frame,grid,grid,false,p->sample(Blocks.OAK_PLANKS.defaultBlockState()))));
        RayResult ray=snapshot.rayCast(START,END,null);
        assertEquals(Status.HIT,ray.status());assertEquals("sable:fixture",ray.space().acousticId());
        assertEquals(BlockPos.containing(plot),ray.localHit().getBlockPos());
        assertEquals(4,ray.worldLocation().x,1e-7);assertEquals(1.5,ray.worldLocation().y,1e-7);assertEquals(1.5,ray.worldLocation().z,1e-7);
        assertEquals(new Vec3(-1,0,0),ray.worldNormal());
        assertTrue(ray.space().getBlockState(ray.localHit().getBlockPos()).is(Blocks.OAK_PLANKS));
        assertEquals(0,plot.distanceTo(frame.worldToLocal(frame.localToWorld(plot))),1e-8);
    }

    @Test void UnknownShipCellOutranksLaterRootWallButNotEarlierKnownWall() {
        Grid ship=new Grid(3,1,1,4,2,2);
        Input unknownShip=input("sable:missing",Frame.identity(),ship,ship,false,p->null);
        Snapshot lateWall=copy(List.of(root(p->p.getX()==6?sample(Blocks.STONE.defaultBlockState()):air()),unknownShip));
        assertEquals(Status.UNKNOWN,lateWall.rayCast(START,END,null).status());
        Snapshot earlyWall=copy(List.of(root(p->p.getX()==2?sample(Blocks.STONE.defaultBlockState()):air()),unknownShip));
        assertEquals(Status.HIT,earlyWall.rayCast(START,END,null).status());
        assertEquals(2,earlyWall.rayCast(START,END,null).worldLocation().x);
    }

    @Test void IgnoreIsSpaceAndLocalBlockSpecificAndDoesNotEraseOtherShip() {
        Grid block=new Grid(4,1,1,5,2,2);
        Snapshot snapshot=copy(List.of(root(p->p.equals(new BlockPos(4,1,1))?sample(Blocks.STONE.defaultBlockState()):air()),
            input("sable:same_coords",Frame.identity(),block,block,false,p->sample(Blocks.OAK_PLANKS.defaultBlockState()))));
        RayResult first=snapshot.rayCast(START,END,null);
        RayResult next=snapshot.rayCast(START,END,new BlockRef(first.space(),first.localHit().getBlockPos()));
        assertEquals(Status.HIT,next.status());assertNotEquals(first.space().acousticId(),next.space().acousticId());
    }

    @Test void CapturedFluidSurfaceAndPartialCollisionShapesAreUsed() {
        Sample fluid=new Sample(Blocks.WATER.defaultBlockState(),Fluids.WATER.defaultFluidState(),Shapes.empty(),Shapes.box(0,0,0,1,.8,1));
        Snapshot water=copy(List.of(root(p->p.getX()==4?fluid:air())));
        assertEquals(Status.HIT,water.rayCast(START,END,null).status());
        assertEquals(Status.MISS,water.rayCast(new Vec3(.5,1.9,1.5),new Vec3(7.5,1.9,1.5),null).status());
        Sample lowerSlab=new Sample(Blocks.STONE_SLAB.defaultBlockState(),Fluids.EMPTY.defaultFluidState(),Shapes.box(0,0,0,1,.5,1),Shapes.empty());
        Snapshot slab=copy(List.of(root(p->p.getX()==4?lowerSlab:air())));
        assertEquals(Status.HIT,slab.rayCast(new Vec3(.5,1.25,1.5),new Vec3(7.5,1.25,1.5),null).status());
        assertEquals(Status.MISS,slab.rayCast(new Vec3(.5,1.75,1.5),new Vec3(7.5,1.75,1.5),null).status());
    }

    @Test void AffineNormalUsesInverseTransposeAndInvalidFramesAreRejected() {
        Frame f=new Frame(Vec3.ZERO,Vec3.ZERO,new Vec3(2,0,0),new Vec3(1,1,0),new Vec3(0,0,3));
        Vec3 v=new Vec3(2,3,4);assertEquals(0,v.distanceTo(f.worldToLocalDirection(f.localToWorldDirection(v))),1e-9);
        Vec3 normal=f.localNormalToWorld(new Vec3(1,0,0));
        assertEquals(0,normal.dot(f.y()),1e-9);assertEquals(0,normal.dot(f.z()),1e-9);
        assertThrows(IllegalArgumentException.class,()->new Frame(Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO));
        assertNull(Grid.of(new AABB(Double.NaN,0,0,1,1,1)));
        assertNull(Grid.of(new AABB(0,0,0,Double.POSITIVE_INFINITY,1,1)));
    }

    @Test void ActualSablePoseIsCopiedWithoutRetainingMutablePoseOrLosingPlotPrecision() {
        var pose=new dev.ryanhcode.sable.companion.math.Pose3d();
        Vec3 anchor=new Vec3(20_481_000.5,100.5,20_483_000.5);
        pose.position().set(4,1,2);pose.rotationPoint().set(anchor.x,anchor.y,anchor.z);
        pose.orientation().rotateY(.7);pose.scale().set(1,1,1);
        Frame copied=Frame.from(pose,anchor);
        Vec3 point=anchor.add(.3,.2,.1),expected=pose.transformPosition(point);
        assertEquals(0,expected.distanceTo(copied.localToWorld(point)),1e-7);
        assertEquals(0,point.distanceTo(copied.worldToLocal(expected)),1e-7);
        pose.position().set(900,800,700);pose.orientation().identity();
        assertEquals(0,expected.distanceTo(copied.localToWorld(point)),1e-7);
    }

    @Test void ShapeFailureIsUnknownAndDoesNotAbortOtherCompletedCells() {
        Snapshot snapshot=copy(List.of(root(p->{if(p.getX()==4)throw new IllegalArgumentException("shape callback failed");return air();})));
        assertEquals(Status.UNKNOWN,snapshot.rayCast(START,END,null).status());
        assertTrue(snapshot.spaces().getFirst().known(BlockPos.ZERO));
    }

    @Test void RaysHandleReverseTravelExactBoundariesAndCornerCrossings() {
        Snapshot snapshot=copy(List.of(root(p->p.getX()==4?sample(Blocks.STONE.defaultBlockState()):air())));
        RayResult reverse=snapshot.rayCast(END,START,null);
        assertEquals(Status.HIT,reverse.status());assertEquals(5,reverse.worldLocation().x);
        assertEquals(new Vec3(1,0,0),reverse.worldNormal());
        assertEquals(Status.HIT,snapshot.rayCast(new Vec3(7,1.5,1.5),START,null).status());
        Snapshot diagonal=copy(List.of(root(p->p.equals(new BlockPos(2,2,2))?sample(Blocks.STONE.defaultBlockState()):air())));
        assertEquals(Status.HIT,diagonal.rayCast(new Vec3(.5,.5,.5),new Vec3(3.5,3.5,3.5),null).status());
        assertEquals(Status.MISS,snapshot.rayCast(START,START,null).status());
    }
}
