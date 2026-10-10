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
    @Test void installedNativeAccessorUsesTheMutableRenderPairAndHasTheGuardedMethod() throws IOException {
        var wrapper=nativeClass("me/erykczy/colorfullighting/accessors/MinecraftWrapper");
        var getter=method(wrapper,"getLevel","()Lme/erykczy/colorfullighting/common/accessors/LevelAccessor;");
        boolean level=false,renderer=false;
        for (var instruction:getter.instructions) if (instruction instanceof FieldInsnNode field) {
            if (field.owner.equals("net/minecraft/client/Minecraft")) {
                level|=field.name.equals("level"); renderer|=field.name.equals("levelRenderer");
            }
        }
        assertTrue(level && renderer,"Pinned native version requires the paired player-world accessor bridge");
    }
    @Test void nativeWorldResetJoinsProducerBeforeClearingCoordinateOnlyStorageAndDynamicState() throws IOException {
        var engine=nativeClass("me/erykczy/colorfullighting/common/ColoredLightEngine");
        method(engine,"getInstance","()Lme/erykczy/colorfullighting/common/ColoredLightEngine;");
        var reset=method(engine,"reset","()V");
        boolean joined=false,cleared=false,dynamic=false;
        for (var instruction:reset.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals("java/lang/Thread") && call.name.equals("join")) joined=true;
            if (call.owner.endsWith("ColoredLightStorage") && call.name.equals("clear")) {
                assertTrue(joined,"Old producer must finish before replacing its world storage"); cleared=true;
            }
            if (call.owner.endsWith("EntityLightManager") && call.name.equals("reset")) dynamic=true;
        }
        assertTrue(cleared && dynamic,"Crossing uses native lifecycle reset rather than dropping the primary sampler");
    }
    @Test void nativeInitialHandoffHasOneRebuildSiteAndDynamicSamplerIsIndependentlyAvailable() throws IOException {
        var engine=nativeClass("me/erykczy/colorfullighting/common/ColoredLightEngine");
        var update=method(engine,"onLightUpdate","()V"); int rebuilds=0; boolean completionConsumed=false;
        for (var instruction:update.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals("java/util/concurrent/atomic/AtomicBoolean") && call.name.equals("compareAndSet"))
                completionConsumed=true;
            if (call.owner.endsWith("accessors/LevelAccessor") && call.name.equals("rebuildAllSections")) {
                assertTrue(completionConsumed,"The handoff must follow native completion consumption"); rebuilds++;
            }
        }
        assertEquals(1,rebuilds);
        method(nativeClass("me/erykczy/colorfullighting/common/EntityLightManager"),"sampleLightColor",
            "(DDD)Lme/erykczy/colorfullighting/common/util/ColorRGB4;");
    }
    @Test void nativeCompletionReloadsWholeRendererButOurWrapperTargetsOnlyThatExactCall() throws IOException {
        var nativeAccessor=nativeClass("me/erykczy/colorfullighting/common/accessors/LevelAccessor");
        assertNotEquals(0,nativeAccessor.access & Opcodes.ACC_INTERFACE);
        var interfaceCall=method(nativeAccessor,"rebuildAllSections","()V");
        assertEquals(0,interfaceCall.access & Opcodes.ACC_STATIC);
        var update=method(nativeClass("me/erykczy/colorfullighting/common/ColoredLightEngine"),"onLightUpdate","()V");
        assertTrue(java.util.Arrays.stream(update.instructions.toArray()).anyMatch(instruction ->
            instruction instanceof MethodInsnNode call && call.owner.equals(nativeAccessor.name)
                && call.name.equals("rebuildAllSections") && call.desc.equals("()V") && call.getOpcode()==Opcodes.INVOKEINTERFACE));
        var rebuild=method(nativeClass("me/erykczy/colorfullighting/accessors/LevelWrapper"),"rebuildAllSections","()V");
        assertEquals(1,calls(rebuild,"net/minecraft/client/renderer/LevelRenderer","allChanged"));
        var hook=compiledClass("qouteall/imm_ptl/core/compat/mixin/colorful/MixinPortalColoredEngineLifecycle");
        var wrapper=method(hook,"ip_finishNativeWarmupBeforeRemesh",
            "(Ljava/lang/Object;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        // Mixin's @Coerce has CLASS retention: the receiver contract lives in this classfile attribute.
        assertNotNull(wrapper.invisibleParameterAnnotations);
        assertNotNull(wrapper.invisibleParameterAnnotations[0]);
        assertTrue(wrapper.invisibleParameterAnnotations[0].stream().anyMatch(a ->
            a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Coerce;")),"Optional native interface receiver must be explicitly coerced");
        var annotation=wrapper.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("onLightUpdate()V"),value(annotation,"method"));
        assertEquals(1,value(annotation,"require"));
        var sites=(java.util.List<?>)value(annotation,"at"); assertEquals(1,sites.size());
        var at=(AnnotationNode)sites.getFirst();
        assertEquals("Lme/erykczy/colorfullighting/common/accessors/LevelAccessor;rebuildAllSections()V",value(at,"target"));
        assertEquals(1,calls(wrapper,"qouteall/imm_ptl/core/lighting/PortalNativeColoredLighting","nativeInitialLightReadyAndRemesh"));
        assertEquals(1,calls(wrapper,"com/llamalad7/mixinextras/injector/wrapoperation/Operation","call"));
        assertEquals(0,calls(wrapper,"net/minecraft/client/renderer/LevelRenderer","allChanged"));
        var capture=method(hook,"ip_captureWarmupGeneration","(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        assertEquals(1,calls(capture,"qouteall/imm_ptl/core/lighting/PortalNativeColoredLighting","nativeWarmupGeneration"));
    }

    @Test void queuedCompletionUsesOnlyLoadedChunksAndExistingSectionRebuilds() throws IOException {
        var adapter=compiledClass("qouteall/imm_ptl/core/lighting/PortalNativeColoredLighting");
        int loadedSnapshots=0,sectionRebuilds=0,unforcedReads=0,hostedLists=0,plotRemesh=0;
        for (var m:adapter.methods) {
            if (m.name.equals("nativeInitialLightReadyAndRemesh"))
                assertEquals(1,calls(m,"qouteall/imm_ptl/core/lighting/PortalColorCompletionRemesh","handoff"));
            for (var instruction:m.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("getCopiedChunkList")) loadedSnapshots++;
                if (call.owner.equals("ipl/sable/client/IplClientHostedLookup") && call.name.equals("getHostedSubLevelsFor")) hostedLists++;
                if (call.owner.endsWith("/SubLevelRenderDispatcher") && call.name.equals("rebuild")) plotRemesh++;
                if (call.name.equals("schedulePortalLightRebuild")) sectionRebuilds++;
                if (call.name.equals("getChunk") && call.desc.endsWith("ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;")) {
                    assertEquals(Opcodes.ICONST_0,call.getPrevious().getOpcode(),"Remesh validation must never load a chunk");
                    unforcedReads++;
                }
                assertFalse(call.name.equals("allChanged") || call.name.equals("releaseAllBuffers"));
            }
        }
        assertEquals(1,loadedSnapshots); assertTrue(sectionRebuilds>=2); assertEquals(1,unforcedReads);
        assertEquals(1,hostedLists); assertEquals(1,plotRemesh);
        var dispatcher=method(compiledClass("dev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher"),
            "rebuild","(Ljava/lang/Iterable;)V");
        assertEquals(0,calls(dispatcher,"net/minecraft/client/renderer/LevelRenderer","allChanged"));
        assertTrue(java.util.Arrays.stream(dispatcher.instructions.toArray()).anyMatch(instruction ->
            instruction instanceof MethodInsnNode call && call.name.equals("getRenderData")));
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
    private ClassNode compiledClass(String name) throws IOException {
        try (var input=getClass().getResourceAsStream("/"+name+".class")) {
            assertNotNull(input); var node=new ClassNode(); new ClassReader(input).accept(node,0); return node;
        }
    }
    private static long calls(MethodNode method,String owner,String name) {
        long count=0;
        for (var instruction:method.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }
    private static Object value(AnnotationNode annotation,String name) {
        for (int i=0;i<annotation.values.size();i+=2) if (annotation.values.get(i).equals(name)) return annotation.values.get(i+1);
        throw new AssertionError("Missing annotation value "+name);
    }
    private static MethodNode method(ClassNode node,String name,String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError(node.name+"."+name+descriptor));
    }
}
