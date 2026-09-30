package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class DhTaaCrossingContractTest {
    @Test void productionShaderAdapterTransfersTheCompletedCameraAndMatrix() throws Exception {
        var type=Class.forName("qouteall.imm_ptl.core.compat.mixin.dh.MixinDhTaaPreviousFrame");
        var instance=type.getConstructor().newInstance();
        var matrix=new com.seibel.distanthorizons.core.util.math.DhMat4f();
        var camera=new com.seibel.distanthorizons.core.util.math.DhVec3d();
        var mf=type.getDeclaredField("previousDhProjMvmMatrix");mf.setAccessible(true);mf.set(instance,matrix);
        var cf=type.getDeclaredField("previousCameraPos");cf.setAccessible(true);cf.set(instance,camera);
        var snapshot=new DhTaaHistory.Snapshot(10,100,7,new org.joml.Matrix4f().perspective(1,1,.1f,1000),
            new org.joml.Matrix4f().rotateY(.4f),new org.joml.Vector3d(123,65,-200));
        ((DhTaaPreviousFrame)instance).ip_acceptPortalHistory(snapshot);
        assertEquals(snapshot.combined(),matrix.createJomlMatrix());
        assertEquals(123,camera.x);assertEquals(65,camera.y);assertEquals(-200,camera.z);
    }
    private ClassNode read(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/"+name+".class")) {
            assertNotNull(in,name); var node=new ClassNode(); new ClassReader(in).accept(node,0); return node;
        }
    }
    @Test void installedMainHistoryHookRunsAfterAllocationAndBeforeSampling() throws Exception {
        String base="com/seibel/distanthorizons/common/render/openGl/postProcessing/antialiasing/";
        var renderer=read(base+"GlDhTaaRenderer_neoforge");
        for(String field:new String[]{"framebufferA","framebufferB","width","height"})
            assertTrue(renderer.fields.stream().anyMatch(f->f.name.equals(field)&&f.desc.equals("I")),field);
        assertTrue(renderer.fields.stream().anyMatch(f->f.name.equals("textureAIsHistory")&&f.desc.equals("Z")));
        var method=renderer.methods.stream().filter(m->m.name.equals("render")).findFirst().orElseThrow();
        int allocations=0, prep=0, sample=0;
        for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call) {
            if(call.name.equals("createFramebuffer")) allocations++;
            if(call.owner.equals(base+"GlDhTaaShader_neoforge")&&call.name.equals("renderPrep")) {
                assertEquals(1,allocations); assertEquals("(III)V",call.desc); prep++;
            }
            if(call.owner.equals(base+"GlDhTaaShader_neoforge")&&call.name.equals("render")) {assertEquals(1,prep);sample++;}
        }
        assertEquals(1,prep);assertEquals(1,sample);
        var shader=read(base+"GlDhTaaShader_neoforge");
        assertTrue(shader.fields.stream().anyMatch(f->f.name.equals("previousDhProjMvmMatrix")
            &&f.desc.equals("Lcom/seibel/distanthorizons/core/util/math/DhMat4f;")&&(f.access&Opcodes.ACC_FINAL)!=0));
        assertTrue(shader.fields.stream().anyMatch(f->f.name.equals("previousCameraPos")
            &&f.desc.equals("Lcom/seibel/distanthorizons/core/util/math/DhVec3d;")&&(f.access&Opcodes.ACC_FINAL)!=0));
    }
    @Test void successfulTeleportHookCannotRunOnRejectedEarlyReturn() throws Exception {
        var node=read("qouteall/imm_ptl/core/teleportation/ClientTeleportationManager");
        var method=node.methods.stream().filter(m->m.name.equals("teleportPlayer")).findFirst().orElseThrow();
        int returns=0,hooks=0;
        for(var instruction:method.instructions) {
            if(instruction.getOpcode()==Opcodes.RETURN) returns++;
            if(instruction instanceof FieldInsnNode field &&field.getOpcode()==Opcodes.PUTSTATIC&&field.name.equals("isTeleportingFrame")) {
                assertEquals(1,returns,"Rejected-teleport return must precede success-only hook");hooks++;
            }
        }
        assertEquals(1,hooks);assertEquals(2,returns);
    }
}
