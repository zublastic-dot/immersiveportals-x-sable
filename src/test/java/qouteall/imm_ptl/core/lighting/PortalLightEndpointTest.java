package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightSnapshot.*;

class PortalLightEndpointTest {
    @Test void savedPhysicalPortalNormalRejectsOnlyItsRotatedReceivingSide() {
        Vec3 savedOverworldNormal=new Vec3(-.999516671067223,.000420215577159,.031084524727999);
        Vec3 netherInward=new Vec3(1,0,0);
        assertNull(PortalLighting.receivingInward(savedOverworldNormal,netherInward,false));
        assertEquals(new Pos(1,0,0),PortalLighting.receivingInward(savedOverworldNormal,netherInward,true));
        assertEquals(new Pos(1,0,0),PortalLighting.receivingInward(netherInward,savedOverworldNormal,false));
        assertNull(PortalLighting.receivingInward(netherInward,savedOverworldNormal,true));
    }

    @Test void rotatedSourceDoesNotDisableCardinalReceivingEndpoint() {
        Vec3 rotated = new Vec3(1,0,0).yRot((float)Math.toRadians(1.8));
        assertNull(PortalLighting.receivingInward(rotated,new Vec3(-1,0,0),false));
        assertEquals(new Pos(-1,0,0),PortalLighting.receivingInward(rotated,new Vec3(-1,0,0),true));
    }

    @Test void reversePortalAdmitsTheSameCardinalRoomAndRejectsItsRotatedTarget() {
        Vec3 rotated = new Vec3(-1,0,0).yRot((float)Math.toRadians(1.8));
        assertEquals(new Pos(1,0,0),PortalLighting.receivingInward(new Vec3(1,0,0),rotated,false));
        assertNull(PortalLighting.receivingInward(new Vec3(1,0,0),rotated,true));
    }

    @Test void allCardinalTargetsRetainTheirPreviousDirection() {
        for(Vec3 n:new Vec3[]{new Vec3(1,0,0),new Vec3(-1,0,0),new Vec3(0,1,0),
                new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1)}) {
            Pos inward=new Pos((int)n.x,(int)n.y,(int)n.z);
            assertEquals(inward,PortalLighting.receivingInward(n,n.scale(-1),false));
            assertEquals(new Pos(-inward.x(),-inward.y(),-inward.z()),
                PortalLighting.receivingInward(n,n.scale(-1),true));
        }
    }

    @Test void twoRotatedTargetsAndMalformedDirectionsStayUnsupported() {
        Vec3 a=new Vec3(1,0,0).yRot(.03f),b=new Vec3(-1,0,0).yRot(.02f);
        assertNull(PortalLighting.receivingInward(a,b,false));
        assertNull(PortalLighting.receivingInward(a,b,true));
        for(Vec3 invalid:new Vec3[]{Vec3.ZERO,new Vec3(Double.NaN,0,0),
                new Vec3(Double.POSITIVE_INFINITY,0,0),new Vec3(1,1,0),new Vec3(2,0,0)})
            assertNull(PortalLighting.receivingInward(invalid,new Vec3(-1,0,0),false));
    }

    private record MappedPlane(Vec3 normal, PortalLighting.PlanePoint points,
                               UnaryOperator<Vec3> transform) {}

    private MappedPlane rotatedPlane(float angle) {
        Vec3 origin=new Vec3(100.5,2,3),destination=new Vec3(.5,2,3);
        Vec3 normal=new Vec3(1,0,0).yRot(angle),height=new Vec3(0,0,1).yRot(angle);
        PortalLighting.PlanePoint points=(u,v)->origin.add(0,u,0).add(height.scale(v));
        UnaryOperator<Vec3> transform=p->p.subtract(origin).yRot(-angle).add(destination);
        return new MappedPlane(normal,points,transform);
    }

    private Sample room(Pos p) {
        return new Sample(p.x()>=-4 && p.x()<=-1 && p.y()>=0 && p.y()<4 && p.z()>=0 && p.z()<6
            ? Cell.OPEN : Cell.CLOSED,new Light(0,0));
    }

    @Test void fullRigidSourceTransformProducesPlanarReceivingSeedsAndBoundedRoomField() {
        var plane=rotatedPlane((float)Math.toRadians(30));
        Map<Pos,Pos> samples=PortalLighting.apertureSamples(4,6,plane.normal,plane.points,plane.transform,true);
        assertEquals(24,samples.size());
        assertTrue(samples.keySet().stream().allMatch(p->p.x()==-1));
        assertTrue(samples.values().stream().map(Pos::x).distinct().count()>1,
            "source sampling must follow its actual rotated plane, not a snapped world-axis plane");
        var result=PortalLightSnapshot.update(this::room,p->{
            assertTrue(samples.containsValue(p));return new Sample(Cell.OPEN,new Light(15,0));
        },samples,new Pos(-1,0,0),null,false);
        assertTrue(result.field().available());
        assertEquals(96,result.field().cells().size());
        assertEquals(new Light(11,0),result.field().cells().get(new Pos(-4,2,3)));
        assertFalse(result.field().cells().containsKey(new Pos(-4,4,3)),"opaque room corner cannot become a seed");
        assertFalse(result.field().cells().containsKey(new Pos(0,2,3)),"field stays on the receiving side");
    }

    @Test void cardinalOrientationDoesNotBypassVoxelPlanarityProof() {
        var plane=rotatedPlane((float)Math.toRadians(30));
        // A deliberately inconsistent transform models a bad/nonplanar seed map.
        // Endpoint admission is not permission to bypass the solver's own proof.
        UnaryOperator<Vec3> skew=p->{Vec3 q=plane.transform.apply(p);return q.add(q.y*.4,0,0);};
        var samples=PortalLighting.apertureSamples(4,6,plane.normal,plane.points,skew,true);
        assertTrue(samples.keySet().stream().map(Pos::x).distinct().count()>1);
        var result=PortalLightSnapshot.update(this::room,p->new Sample(Cell.OPEN,new Light(15,15)),
            samples,new Pos(-1,0,0),null,false);
        assertFalse(result.field().available());assertEquals("nonplanar aperture",result.field().reason());
    }

    @Test void cardinalMappingAndReverseDirectionKeepExistingSamplePairs() {
        var plane=rotatedPlane(0);
        var receiving=PortalLighting.apertureSamples(4,6,plane.normal,plane.points,plane.transform,true);
        var originating=PortalLighting.apertureSamples(4,6,plane.normal,plane.points,plane.transform,false);
        assertEquals(24,originating.size());
        receiving.forEach((target,source)->assertEquals(target,originating.get(source)));
        assertEquals(new Pos(101,0,0),receiving.get(new Pos(-1,0,0)));
    }
}
