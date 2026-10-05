package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class PortalNativeColorContractTest {
    @Test void installedRenderViewCarriesTheExactRootAndRgbFactoryPreservesNativeVertexEncoding() throws IOException {
        var view=nativeClass("dev/colorfullighting/compat/level/RenderLevelView");
        method(view,"colorfulLighting$getRootLevel","()Ljava/lang/Object;");
        var rgb=nativeClass("me/erykczy/colorfullighting/common/util/ColorRGB8");
        var factory=method(rgb,"fromRGB8","(III)Lme/erykczy/colorfullighting/common/util/ColorRGB8;");
        assertNotEquals(0,factory.access & Opcodes.ACC_STATIC);
        method(nativeClass("dev/colorfullighting/compat/ColorfulLightGate"),"trySampleColorful",
            "(Ljava/lang/Object;DDD)Lme/erykczy/colorfullighting/common/util/ColorRGB8;");
    }
    @Test void installedColorDefinitionSettersHaveExactlyTheFiveGuardedReturnSites() throws IOException {
        var config=nativeClass("me/erykczy/colorfullighting/common/Config"); int returns=0;
        for (String name:new String[]{"setColorEmitters","setColorFilters","setStateColorEmitters","setStateColorFilters","setUserBlockOverrides"}) {
            var setter=method(config,name,name.equals("setUserBlockOverrides") ? "(Ljava/util/Map;)V" : "(Ljava/util/HashMap;)V");
            assertNotEquals(0,setter.access & Opcodes.ACC_STATIC);
            for (var instruction:setter.instructions) if (instruction.getOpcode()==Opcodes.RETURN) returns++;
        }
        assertEquals(5,returns,"Resource/editor reload invalidation must cover every native setter return");
    }
    @Test void workerCallbackIsIndependentOfExperimentAndCannotReadLiveBlocksOrGlobalMinecraftLevel() throws IOException {
        var node=new ClassNode();
        try (var input=getClass().getResourceAsStream("/qouteall/imm_ptl/core/lighting/PortalNativeColoredLighting.class")) {
            assertNotNull(input); new ClassReader(input).accept(node,0);
        }
        var sample=method(node,"sample","(Ljava/lang/Object;DDDLjava/lang/Object;)Ljava/lang/Object;");
        for (var instruction:sample.instructions) {
            if (instruction instanceof FieldInsnNode field)
                assertFalse(field.name.equals("experimentalPortalColoredLighting"));
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.equals("net/minecraft/client/Minecraft"));
                assertFalse(call.name.equals("getBlockState"));
                assertFalse(call.owner.contains("ColoredLightEngine"));
            }
        }
        assertTrue(PortalColoredLightCompatibility.supports("2.5.1"));
        assertFalse(PortalColoredLightCompatibility.supports(null));
        assertFalse(PortalColoredLightCompatibility.supports("2.5.2"));
    }
    private static ClassNode nativeClass(String name) throws IOException {
        String path=System.getProperty("colorfulJar","");
        if (path.isBlank()) path=System.getenv("IP_PORTAL_TEST_COLORFUL_JAR");
        Assumptions.assumeTrue(path!=null && !path.isBlank(),"Installed Colorful ABI input is required");
        try (var jar=new JarFile(path)) {
            var entry=jar.getJarEntry(name+".class"); assertNotNull(entry,name);
            try (var input=jar.getInputStream(entry)) {
                var node=new ClassNode(); new ClassReader(input).accept(node,0); return node;
            }
        }
    }
    private static MethodNode method(ClassNode node,String name,String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError(node.name+"."+name+descriptor));
    }
}
