package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class DhVanillaCoverageContractTest {
    private ClassNode read(String path) throws Exception {
        try (var in=getClass().getResourceAsStream("/"+path+".class")) {
            assertNotNull(in);var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    @Test void installedDhDerivesNearPlaneFromTheScopedRenderDistance() throws Exception {
        var node=read("com/seibel/distanthorizons/core/util/RenderUtil");
        var method=node.methods.stream().filter(m->m.name.equals("getNearClipPlaneDistanceInBlocks")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false)
            .anyMatch(i->i instanceof MethodInsnNode c&&c.name.equals("getRenderDistance")));
    }
    @Test void coverageUsesNonCreatingChunkLookupAndKeepsShaderAndPortalGuards() throws Exception {
        var node=read("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering");
        boolean chunks=false,shader=false,portal=false,empty=false;
        for(var m:node.methods) if(m.name.equals("vanillaCoverageDistance")||m.name.equals("coverageBlocks")||m.name.startsWith("lambda$coverageBlocks")) {
            for(var i:m.instructions) {
                if(i instanceof MethodInsnNode c) {
                    assertNotEquals("hasChunk",c.name,"ClientLevel.hasChunk can return true for absent chunks");
                    if(c.name.equals("getChunk")) {
                        chunks=true;
                        assertEquals(org.objectweb.asm.Opcodes.ICONST_0,c.getPrevious().getOpcode(),"Never create/request chunks while rendering");
                    }
                    if(c.name.equals("isShaders"))shader=true;
                    if(c.name.equals("isRendering")&&c.owner.endsWith("/PortalRendering"))portal=true;
                }
                if(i instanceof TypeInsnNode t&&t.desc.endsWith("/EmptyLevelChunk"))empty=true;
            }
        }
        assertTrue(chunks&&shader&&portal&&empty);
    }
    @Test void shaderNativeDistanceReturnsUnchangedBeforeAnyCoverageLookup() throws Exception {
        var node=read("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering");
        var method=node.methods.stream().filter(m->m.name.equals("vanillaCoverageDistance")).findFirst().orElseThrow();
        MethodInsnNode shaderQuery=null;
        for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call && call.name.equals("isShaders")) shaderQuery=call;
        assertNotNull(shaderQuery);
        // True falls straight through to `return requested`; false jumps to the
        // existing no-shader path. This checks the production guard, not a copy.
        assertEquals(org.objectweb.asm.Opcodes.IFEQ,shaderQuery.getNext().getOpcode());
        var load=shaderQuery.getNext().getNext();
        while(load.getOpcode()<0) load=load.getNext();
        assertInstanceOf(VarInsnNode.class,load);
        assertEquals(org.objectweb.asm.Opcodes.ILOAD,load.getOpcode());
        assertEquals(0,((VarInsnNode)load).var);
        assertEquals(org.objectweb.asm.Opcodes.IRETURN,load.getNext().getOpcode());
        for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call) {
            assertNotEquals("isChunkMeshReady",call.name,"The established no-shader path remains loaded-only");
            assertNotEquals("coverageBlocks",call.name,"Shader readiness must not feed DH's native near plane");
        }
    }
}
