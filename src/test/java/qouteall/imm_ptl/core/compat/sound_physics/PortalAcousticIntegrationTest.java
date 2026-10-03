package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;
import qouteall.imm_ptl.core.teleportation.PortalSoundPath;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.compat.sound_physics.PortalAcousticSnapshot.*;

/** Always-run physical-leg regressions, plus an explicitly reported installed-provider contract. */
class PortalAcousticIntegrationTest {
    @BeforeAll static void bootstrap() { PortalBlockTestBootstrap.initialize(); }
    private static final Grid SOURCE = new Grid(-6,-6,-6,8,8,8);
    private static final Grid LISTENER = new Grid(94,44,14,109,59,29);
    private static final PortalSoundPath.Frame PORTAL = new PortalSoundPath.Frame(Vec3.ZERO,
        new Vec3(100,50,20),new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1),new Vec3(0,1,0),2,3);
    private static final Vec3 EMITTER = new Vec3(1.5,1.5,3.5), EAR = new Vec3(104,51.5,21.5);
    private static final String SPA_SHA = "ecaadadaedc4b7d524f45161c583aeab0de2da4aba4cc04a67632bbdf0263a76";

    private static Sample sample(BlockState state) {
        if(state==null)return null;
        return new Sample(state,state.getFluidState(),state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,CollisionContext.empty()),
            state.getFluidState().getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO));
    }
    private static Snapshot snapshot(Grid grid,Function<BlockPos,BlockState> blocks) {
        var input=new Input("root",Frame.identity(),grid,grid,true,p->sample(blocks.apply(p)),-64,384);
        return new CaptureJob(grid.box(),List.of(input),()->0).advance(1);
    }
    private static Snapshot air(Grid grid) { return snapshot(grid,p->Blocks.AIR.defaultBlockState()); }
    private static PortalSoundManager.Route route() {
        var path=PortalSoundPath.solve(PORTAL,EMITTER,EAR);assertNotNull(path);
        return new PortalSoundManager.Route(null,null,EMITTER,path.entry(),path.exit(),EAR,path.presentation(),path.virtualSource(),
            path.distance(),17,new UUID(1,2),true,true,PORTAL);
    }
    private static PortalAcousticScene scene(Snapshot source,Snapshot listener) { return new PortalAcousticScene(route(),source,listener); }

    @Test void RotatedPortalLegsKeepDifferentMaterialsInTheirOriginalWorldCoordinates() {
        Snapshot source=snapshot(SOURCE,p->p.getZ()==2?Blocks.WHITE_WOOL.defaultBlockState():Blocks.AIR.defaultBlockState());
        Snapshot listener=snapshot(LISTENER,p->p.getX()==102?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        Vec3 virtual=PORTAL.transform(EMITTER);
        var split=PortalAcousticScene.split(PORTAL,virtual,EAR);assertNotNull(split);assertTrue(split.open());
        RayResult first=source.rayCast(EMITTER,PORTAL.inverse(split.point()),null);
        RayResult second=listener.rayCast(split.point(),EAR,null);
        assertEquals(Status.HIT,first.status());assertEquals(Status.HIT,second.status());
        assertTrue(first.space().getBlockState(first.localHit().getBlockPos()).is(Blocks.WHITE_WOOL));
        assertTrue(second.space().getBlockState(second.localHit().getBlockPos()).is(Blocks.STONE));
        assertEquals(new BlockPos(1,1,2),first.localHit().getBlockPos());
        assertEquals(new BlockPos(102,51,21),second.localHit().getBlockPos());
        assertEquals(97,PORTAL.transform(first.worldLocation()).x,1e-12);
        assertEquals(102,second.worldLocation().x,1e-12);
        assertEquals(0,new Vec3(-1,0,0).distanceTo(PORTAL.transformVector(first.worldNormal())),1e-12);
    }

    @Test void UnknownOnEitherLegStaysUnknownEvenWhenOtherWorldHasAirAtTheSameNumbers() {
        Snapshot unknownSource=snapshot(SOURCE,p->p.getZ()==2?null:Blocks.AIR.defaultBlockState());
        Snapshot unknownListener=snapshot(LISTENER,p->p.getX()==102?null:Blocks.AIR.defaultBlockState());
        var path=route();
        assertEquals(Status.UNKNOWN,unknownSource.rayCast(EMITTER,path.entryPosition(),null).status());
        assertEquals(Status.UNKNOWN,unknownListener.rayCast(path.exitPosition(),EAR,null).status());
        assertEquals(Status.MISS,air(SOURCE).rayCast(EMITTER,path.entryPosition(),null).status());
        assertEquals(Status.MISS,air(LISTENER).rayCast(path.exitPosition(),EAR,null).status());
    }

    @Test void FractionalRotatedApertureDoesNotAdmitRaysPastItsActualEdge() {
        var fractional=new PortalSoundPath.Frame(PORTAL.origin(),PORTAL.destination(),PORTAL.sourceU(),PORTAL.sourceV(),
            PORTAL.destinationU(),PORTAL.destinationV(),1.5,2.25);
        Vec3 edge=PORTAL.destination().add(PORTAL.destinationU().scale(1.5));
        assertTrue(PortalAcousticScene.split(fractional,edge.add(-3,0,0),edge.add(3,0,0)).open());
        Vec3 outside=edge.add(PORTAL.destinationU().scale(.0001));
        assertFalse(PortalAcousticScene.split(fractional,outside.add(-3,0,0),outside.add(3,0,0)).open());
    }

    @Test void InstalledSpaProxyPreservesMaterialsDefaultsUnknownAuthorityAndWorldTransforms() throws Throwable {
        String configured=System.getProperty("ip.portal.test.spaJar");
        if(configured==null || configured.isBlank()) {
            System.out.println("NOT_EXECUTED installed SPA proxy contract: set ip.portal.test.spaJar and add that JAR to testRuntimeOnly");
            return;
        }
        Path jar=Path.of(configured).toRealPath();
        assertEquals(SPA_SHA,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        Class<?> sceneType=Class.forName("com.sonicether.soundphysics.acoustic.AcousticScene");
        assertEquals(jar,Path.of(sceneType.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
            "The real installed contract, not a fixture/shadow class, must supply the interface");

        Object clear=scene(air(SOURCE),air(LISTENER)).asSpaScene();
        Vec3 virtual=PORTAL.transform(EMITTER);
        Object miss=call(clear,"rayCast",virtual,EAR,null);
        assertEquals(HitResult.Type.MISS,((BlockHitResult)call(miss,"localHit")).getType());
        assertEquals(true,call(miss,"authoritative"));assertEquals(true,call(clear,"isSegmentAuthoritative",virtual,EAR));
        assertEquals(false,call(clear,"supportsOrderedOcclusionSegments"));
        assertNotNull(call(clear,"reflectionGeometryIdentity",EAR,2.0));
        assertNotNull(call(clear,"portalBlockIdentity",call(clear,"blockAt",EAR)));

        Snapshot sourceWall=snapshot(SOURCE,p->p.getZ()==2?Blocks.WHITE_WOOL.defaultBlockState():Blocks.AIR.defaultBlockState());
        Object sourceScene=scene(sourceWall,air(LISTENER)).asSpaScene();
        Object sourceHit=call(sourceScene,"rayCast",virtual,EAR,null);
        Object sourceRef=call(sourceHit,"blockRef");
        assertTrue(((BlockState)call(sourceRef,"blockState")).is(Blocks.WHITE_WOOL));
        assertEquals(new BlockPos(1,1,2),call(sourceHit,"blockPos"));
        assertEquals(97,((Vec3)call(sourceHit,"worldLocation")).x,1e-12);
        assertEquals(0,new Vec3(-1,0,0).distanceTo((Vec3)call(sourceHit,"worldNormal")),1e-12);
        Object sourceSpace=call(sourceHit,"space");
        assertEquals("ip:source:root",call(sourceSpace,"acousticId"));
        Vec3 localPoint=new Vec3(1.25,1.75,2.5);
        Vec3 presented=(Vec3)call(sourceScene,"toWorldPosition",sourceRef,localPoint);
        assertEquals(0,PORTAL.transform(localPoint).distanceTo(presented),1e-12);
        assertEquals(0,localPoint.distanceTo((Vec3)call(sourceScene,"toLocalPosition",sourceRef,presented)),1e-12);
        Object resolved=call(sourceScene,"resolveBlockRef","ip:source:root",new BlockPos(1,1,2));
        assertTrue(((BlockState)call(resolved,"blockState")).is(Blocks.WHITE_WOOL));
        Object ignored=call(sourceScene,"rayCast",virtual,EAR,sourceRef);
        assertEquals(HitResult.Type.MISS,((BlockHitResult)call(ignored,"localHit")).getType());

        Snapshot listenerWall=snapshot(LISTENER,p->p.getX()==102?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        Object receiving=scene(air(SOURCE),listenerWall).asSpaScene();
        Object receivingHit=call(receiving,"rayCast",virtual,EAR,null);
        assertTrue(((BlockState)call(call(receivingHit,"blockRef"),"blockState")).is(Blocks.STONE));
        assertEquals("ip:listener:root",call(call(receivingHit,"space"),"acousticId"));
        assertEquals(102,((Vec3)call(receivingHit,"worldLocation")).x,1e-12);

        Snapshot missing=snapshot(SOURCE,p->p.getZ()==2?null:Blocks.AIR.defaultBlockState());
        Object unknown=scene(missing,air(LISTENER)).asSpaScene();
        Object blocked=call(unknown,"rayCast",virtual,EAR,null);
        assertEquals(false,call(blocked,"authoritative"));assertEquals(false,call(unknown,"isSegmentAuthoritative",virtual,EAR));
        assertNotEquals("none",call(unknown,"segmentAuthorityFailure",virtual,EAR));

        Vec3 outsideFrom=PORTAL.destination().add(-3,0,3),outsideTo=PORTAL.destination().add(3,0,3);
        Object outside=call(clear,"rayCast",outsideFrom,outsideTo,null);
        assertEquals(false,call(outside,"authoritative"),"An aperture boundary is not a real infinite obsidian wall");
        assertEquals(false,call(clear,"isSegmentAuthoritative",outsideFrom,outsideTo));
        System.out.println("EXECUTED installed SPA proxy contract: "+SPA_SHA+" material/transform/authority/default-method cases passed");
    }

    private static Object call(Object receiver,String name,Object... args) throws Throwable {
        for(Method method:receiver.getClass().getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            Class<?>[] parameters=method.getParameterTypes();boolean matches=true;
            for(int i=0;i<args.length;i++) if(args[i]!=null&&!boxed(parameters[i]).isInstance(args[i])){matches=false;break;}
            if(!matches)continue;
            try{return method.invoke(receiver,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
        throw new NoSuchMethodException(receiver.getClass().getName()+"."+name);
    }
    private static Class<?> boxed(Class<?> c) { return c==double.class?Double.class:c==int.class?Integer.class:c==long.class?Long.class:c==boolean.class?Boolean.class:c; }
}
