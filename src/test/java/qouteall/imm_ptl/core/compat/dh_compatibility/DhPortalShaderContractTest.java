package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalShaderContractTest {
    private static ClassNode node(byte[] bytes) { var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node; }
    private ClassNode node(String path) throws Exception {
        try(var input=getClass().getResourceAsStream("/"+path+".class")) { assertNotNull(input,path);return node(input.readAllBytes()); }
    }
    private void sodiumContract(ClassNode manager) {
        var method=manager.methods.stream().filter(m->m.name.equals("isSectionBuilt")&&m.desc.equals("(III)Z")).findFirst().orElseThrow();
        assertTrue((method.access&Opcodes.ACC_PUBLIC)!=0);
        boolean built=false;
        for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call) {
            built|=call.name.equals("isBuilt");
            assertNotEquals("isSectionVisible",call.name,"Readiness must not depend on camera-facing visibility");
            assertFalse(call.name.contains("schedule")||call.name.contains("submit"),"Readiness must not queue work");
        }
        assertTrue(built);
    }
    @Test void compileTimeSodiumExposesNonMutatingMeshReadiness() throws Exception {
        sodiumContract(node("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager"));
    }
    @Test @EnabledIfSystemProperty(named="ip.portal.test.sodiumJar",matches=".+")
    void installedSodiumNestedModRetainsTheExactMeshReadinessAbi() throws Exception {
        try(var jar=new ZipFile(System.getProperty("ip.portal.test.sodiumJar"))) {
            var entry=jar.stream().filter(e->e.getName().startsWith("META-INF/jarjar/")
                &&e.getName().contains("sodium-neoforge-")&&e.getName().endsWith("-mod.jar")).findFirst().orElseThrow();
            try(var nested=new ZipInputStream(new ByteArrayInputStream(jar.getInputStream(entry).readAllBytes()))) {
                for(var file=nested.getNextEntry();file!=null;file=nested.getNextEntry()) {
                    if(file.getName().equals("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.class")) {
                        sodiumContract(node(nested.readAllBytes()));return;
                    }
                }
            }
        }
        fail("Installed Sodium has no RenderSectionManager");
    }
    @Test void actualIrisLodProgramsExposeTheLateUniformStageAndUniformUploadNeverChangesFar() throws Exception {
        var iris=node("net/irisshaders/iris/compat/dh/IrisLodRenderProgram");
        assertEquals(1,iris.methods.stream().filter(m->m.name.equals("fillUniformData")).count());
        for(String name:new String[]{"unbind","free"}) assertTrue(iris.methods.stream().anyMatch(m->m.name.equals(name)));
        var hook=node("qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisPortalSunLodProgram");
        var late=hook.methods.stream().filter(m->m.name.equals("ip_bindSun")).findFirst().orElseThrow();
        assertTrue(late.visibleAnnotations!=null||late.invisibleAnnotations!=null);
        boolean independentUpload=false;
        for(var instruction:late.instructions) if(instruction instanceof MethodInsnNode call
            &&call.owner.endsWith("/DhPortalShaderCoverage")&&call.name.equals("bind")) independentUpload=true;
        assertTrue(independentUpload,"DH uniform must upload even when PortalShaderGpu has no sunlight region");
        var uniform=node("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalShaderCoverage");
        for(var method:uniform.methods) if(method.name.equals("bind")) for(var instruction:method.instructions) {
            if(instruction instanceof LdcInsnNode constant) assertNotEquals("far",constant.cst);
            if(instruction instanceof MethodInsnNode call) assertFalse(call.name.contains("Framebuffer")||call.name.contains("Clear"));
        }
    }
    @Test void irisDrawAndDepthReconstructionHaveDifferentUpdateScopes() throws Exception {
        var matrices=node("net/irisshaders/iris/uniforms/MatrixUniforms");
        var dhMatrices=matrices.methods.stream().filter(m->m.name.equals("addDHMatrix")).findFirst().orElseThrow();
        int perFrame=0;
        for(var instruction:dhMatrices.instructions) if(instruction instanceof FieldInsnNode field
            &&field.name.equals("PER_FRAME")) perFrame++;
        assertTrue(perFrame>=2,"Iris caches the DH projection and inverse once per frame");
        var event=node("net/irisshaders/iris/compat/dh/LodRendererEvents$13");
        int nearReads=0,perspectives=0;
        for(var method:event.methods) for(var instruction:method.instructions) {
            if(instruction instanceof FieldInsnNode field && field.name.equals("nearClipPlane")) nearReads++;
            if(instruction instanceof MethodInsnNode call && call.name.equals("setPerspective")) perspectives++;
        }
        assertTrue(nearReads>=2&&perspectives>=2,
            "Opaque and translucent DH draws rebuild projection from their own event near plane");
    }
}
