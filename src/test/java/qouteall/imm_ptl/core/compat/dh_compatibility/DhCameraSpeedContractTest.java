package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class DhCameraSpeedContractTest {
    private ClassNode read(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/"+name+".class")) {
            assertNotNull(in,name);var n=new ClassNode();new ClassReader(in).accept(n,0);return n;
        }
    }
    @Test void installedDhSamplesRawPositionIntoFortySampleAverageUsedByNearClip() throws Exception {
        var client=read("com/seibel/distanthorizons/core/api/internal/ClientApi");
        assertTrue(client.fields.stream().anyMatch(f->f.name.equals("lastCameraPosForSpeedCheck")&&f.desc.equals("Lcom/seibel/distanthorizons/core/util/math/DhVec3d;")));
        assertTrue(client.fields.stream().anyMatch(f->f.name.equals("msSinceLastSpeedCheck")&&f.desc.equals("J")));
        boolean forty=false,distance=false,add=false,fifty=false;
        for(var m:client.methods) for(var i:m.instructions) {
            if(m.name.equals("<init>")&&i instanceof IntInsnNode n&&n.operand==40) forty=true;
            if(m.name.equals("renderLodLayer")&&i instanceof LdcInsnNode n&&Long.valueOf(50).equals(n.cst)) fifty=true;
            if(m.name.equals("renderLodLayer")&&i instanceof MethodInsnNode c) {
                if(c.owner.endsWith("/DhVec3d")&&c.name.equals("getDistance"))distance=true;
                if(c.owner.endsWith("/RollingAverage")&&c.name.equals("add"))add=true;
            }
        }
        assertTrue(forty&&fifty&&distance&&add,"Inspected speed window and raw-position sampling contract");
        var util=read("com/seibel/distanthorizons/core/util/RenderUtil");boolean speed=false,threshold=false;
        var near=util.methods.stream().filter(m->m.name.equals("getNearClipPlaneInBlocks")).findFirst().orElseThrow();
        for(var i:near.instructions) {
            if(i instanceof MethodInsnNode c&&c.name.equals("getAvgCameraSpeed"))speed=true;
            if(i instanceof LdcInsnNode n&&Double.valueOf(10).equals(n.cst))threshold=true;
        }
        assertTrue(speed&&threshold,"Near-clip/fade adaptation consumes the speed average above 10 blocks/s");
    }
    @Test void successOnlyPortalHookUsesFullPointTransformAndDoesNotClearTheSpeedAverage() throws Exception {
        var hook=read("qouteall/imm_ptl/core/compat/mixin/dh/MixinDhPortalCrossing");
        assertTrue(hook.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .anyMatch(i->i instanceof MethodInsnNode c&&c.owner.endsWith("/DhPortalMotion")&&c.name.equals("crossed")));
        var adapter=read("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalMotion");boolean fullPoint=false;
        for(var m:adapter.methods)for(var i:m.instructions) if(i instanceof MethodInsnNode c&&c.owner.endsWith("/Portal")&&c.name.equals("transformPoint"))fullPoint=true;
        assertTrue(fullPoint,"Translation, rotation and scale must all rebase the previous point");
        var mixin=read("qouteall/imm_ptl/core/compat/mixin/dh/MixinDhClientApi");
        var method=mixin.methods.stream().filter(m->m.name.equals("ip_rebaseCameraSpeed")).findFirst().orElseThrow();
        for(var i:method.instructions) {
            if(i instanceof MethodInsnNode c)assertFalse(c.owner.endsWith("/RollingAverage"));
            if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD)assertEquals("lastCameraPosForSpeedCheck",f.name);
        }
    }
}
