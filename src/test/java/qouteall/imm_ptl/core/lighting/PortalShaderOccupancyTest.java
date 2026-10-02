package qouteall.imm_ptl.core.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;

class PortalShaderOccupancyTest {
    @BeforeAll static void bootstrap() { PortalBlockTestBootstrap.initialize(); }
    private record Fixture(PortalShaderLighting.Aperture aperture,PortalLightSnapshot.Snapshot snapshot,
                           int axis,Pos inward) {}

    /** A three-block-deep air room; its portal-placeholder plane is non-air in the ambient snapshot. */
    private static Fixture room(int axis,int sign) {
        int[] n=new int[3];n[axis]=sign;Pos inward=new Pos(n[0],n[1],n[2]);
        var aperture=new HashMap<Pos,Pos>();var geometry=new HashMap<Pos,PortalLightSnapshot.Sample>();
        var lights=new HashMap<Pos,Light>();var replacement=new HashMap<Pos,Float>();
        for(int a=-1;a<=1;a++) for(int b=-1;b<=1;b++) {
            Pos seed=position(axis,sign,a,b);aperture.put(seed,seed);
            for(int depth=1;depth<=3;depth++) {
                Pos p=position(axis,sign*depth,a,b);lights.put(p,new Light(11-depth,3));replacement.put(p,.75f);
                geometry.put(p,new PortalLightSnapshot.Sample(Cell.OPEN,new Light(0,0)));
            }
        }
        // Dense topology halo includes the plane and its surrounding frame, all non-air.
        for(int a=-2;a<=2;a++) for(int b=-2;b<=2;b++)
            geometry.put(position(axis,0,a,b),new PortalLightSnapshot.Sample(Cell.CLOSED,new Light(0,0)));
        var field=new Result(Map.copyOf(lights),Map.copyOf(replacement),"transport");
        var snapshot=new PortalLightSnapshot.Snapshot(Map.copyOf(aperture),inward,Map.copyOf(geometry),Map.of(),
            null,Map.of(),field);
        Vec3 normal=new Vec3(n[0],n[1],n[2]);
        Vec3 u=axis==0?new Vec3(0,1,0):new Vec3(1,0,0);
        Vec3 v=axis==2?new Vec3(0,1,0):new Vec3(0,0,1);
        var a=new PortalShaderLighting.Aperture(null,null,new Vec3(.5,.5,.5),normal,u,v,3,3,
            Vec3.ZERO,normal,u,v,new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1),p->p,aperture);
        return new Fixture(a,snapshot,axis,inward);
    }
    private static Pos position(int axis,int depth,int a,int b) {
        return switch(axis) {case 0->new Pos(depth,a,b);case 1->new Pos(a,depth,b);default->new Pos(a,b,depth);};
    }
    private static float[] voxel(float[] atlas,Pos min,Pos p) {
        int i=(((p.z()-min.z())*32+p.y()-min.y())*32+p.x()-min.x())*4;
        return java.util.Arrays.copyOfRange(atlas,i,i+4);
    }
    @Test void actualOpaquePortalPlaceholderIsCrossWorldOccupancyOnlyForAllSixReceivingDirections() {
        for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
            Fixture f=room(axis,sign);var reads=new HashSet<Pos>();
            var placeholder=PortalPlaceholderBlock.instance.defaultBlockState()
                .setValue(PortalPlaceholderBlock.AXIS,Direction.Axis.values()[axis]);
            assertEquals(15,placeholder.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO));
            assertFalse(placeholder.isAir());
            var layer=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,p->{
                assertEquals(0,p.component(f.axis));assertTrue(reads.add(p));
                return PortalShaderLighting.apertureOccupancy(placeholder,EmptyBlockGetter.INSTANCE,
                    new BlockPos(p.x(),p.y(),p.z()),f.inward);
            },Map.of());
            assertEquals(9,reads.size(),"Only the aperture plane, not its surrounding frame, is read");
            Pos min=PortalShaderLighting.atlasMin(f.snapshot,layer);
            assertEquals(sign>0?0:-3,min.component(axis));
            float[] packed=PortalShaderLighting.packCells(f.snapshot,min,layer);
            for(Pos p:layer.keySet()) assertArrayEquals(new float[]{0,0,0,1},voxel(packed,min,p));
            for(var value:f.snapshot.field().cells().entrySet()) {
                assertArrayEquals(new float[]{value.getValue().sky()/15f,.2f,.75f,1},voxel(packed,min,value.getKey()));
            }
            assertEquals(Cell.CLOSED,f.snapshot.geometry().get(position(axis,0,0,0)).cell(),
                "Cross-world ray occupancy must not modify ambient topology");
            assertEquals(15,placeholder.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),
                "The local-world light barrier must remain opaque");
        }
    }
    @Test void actualApertureClassifierKeepsWrongAxisWoolAndUnknownBlocked() {
        for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
            Fixture f=room(axis,sign);
            Pos wrongAxis=position(axis,0,-1,0),wool=position(axis,0,0,0),unknown=position(axis,0,1,0);
            var placeholder=PortalPlaceholderBlock.instance.defaultBlockState()
                .setValue(PortalPlaceholderBlock.AXIS,Direction.Axis.values()[(axis+1)%3]);
            assertEquals(15,placeholder.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO));
            var layer=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,p->
                PortalShaderLighting.apertureOccupancy(p.equals(wrongAxis)?placeholder:
                    p.equals(wool)?Blocks.WHITE_WOOL.defaultBlockState():
                    p.equals(unknown)?null:Blocks.GLASS.defaultBlockState(),EmptyBlockGetter.INSTANCE,
                    new BlockPos(p.x(),p.y(),p.z()),f.inward),Map.of());
            assertEquals(Cell.CLOSED,layer.get(wrongAxis));assertEquals(Cell.CLOSED,layer.get(wool));
            assertEquals(Cell.UNKNOWN,layer.get(unknown));
            Pos min=PortalShaderLighting.atlasMin(f.snapshot,layer);
            float[] packed=PortalShaderLighting.packCells(f.snapshot,min,layer);
            for(Pos blocked:new Pos[]{wrongAxis,wool,unknown}) assertArrayEquals(new float[4],voxel(packed,min,blocked));
            assertArrayEquals(new float[]{0,0,0,1},voxel(packed,min,position(axis,0,0,1)));
            assertEquals(Cell.CLOSED,PortalShaderLighting.apertureOccupancy(placeholder,EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO,new Pos(1,1,0)),"Unvalidated non-cardinal inward cannot admit a placeholder");
        }
    }
    @Test void opaqueAndNeverObservedPlaneCellsRemainBlocked() {
        Fixture f=room(0,1);Pos concrete=new Pos(0,0,0),unknown=new Pos(0,1,0);
        var layer=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,
            p->p.equals(concrete)?Cell.CLOSED:p.equals(unknown)?Cell.UNKNOWN:Cell.OPEN,Map.of());
        Pos min=PortalShaderLighting.atlasMin(f.snapshot,layer);
        float[] packed=PortalShaderLighting.packCells(f.snapshot,min,layer);
        assertArrayEquals(new float[4],voxel(packed,min,concrete));
        assertArrayEquals(new float[4],voxel(packed,min,unknown));
        assertArrayEquals(new float[]{0,0,0,1},voxel(packed,min,new Pos(0,-1,0)));
    }
    @Test void nonAirToNonAirMutationRefreshesOccupancyEvenWithUnchangedAmbientTopology() {
        Fixture f=room(0,-1);Pos changed=new Pos(0,0,0);
        var clear=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,p->Cell.OPEN,Map.of());
        var blocked=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,
            p->p.equals(changed)?Cell.CLOSED:Cell.OPEN,clear);
        assertNotEquals(clear,blocked);
        assertEquals(Cell.OPEN,clear.get(changed),"Published observations are immutable");
        assertEquals(Cell.CLOSED,blocked.get(changed));
        assertTrue(f.snapshot.geometry().containsKey(changed),"Runtime block invalidation watches this halo cell");
        var reopened=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,p->Cell.OPEN,blocked);
        assertEquals(clear,reopened);
    }
    @Test void unloadedPlaneRetainsOnlyActualPriorObservations() {
        Fixture f=room(1,1);Pos closed=new Pos(0,0,0),unknown=new Pos(1,0,0);
        var previous=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,
            p->p.equals(closed)?Cell.CLOSED:p.equals(unknown)?Cell.UNKNOWN:Cell.OPEN,Map.of());
        var unloaded=PortalShaderLighting.observeApertureLayer(f.aperture,f.snapshot,p->Cell.UNKNOWN,previous);
        assertEquals(previous,unloaded);assertEquals(Cell.CLOSED,unloaded.get(closed));
        assertEquals(Cell.UNKNOWN,unloaded.get(unknown));
    }
    @Test void atlasPackingCannotSilentlyTruncateOverlargeOrUnknownPadding() {
        Fixture f=room(2,1);
        var invalid=Map.of(new Pos(0,0,32),Cell.UNKNOWN);
        assertThrows(IllegalArgumentException.class,()->PortalShaderLighting.packCells(f.snapshot,new Pos(-1,-1,0),invalid));
    }
    @Test void ambientUniformBoundsUseOriginalCellsInExpandedAtlasCoordinates() {
        assertEquals(new Vec3(1.5,.5,.5),PortalShaderGpu.ambientCenter(new Pos(15,40,-8),new Pos(14,40,-8)));
        assertEquals(new Vec3(3.5,5.5,7.5),PortalShaderGpu.ambientCenter(new Pos(17,45,-1),new Pos(14,40,-8)));
    }
}
