package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Reads the actual pinned Iris dependency, without executing its Minecraft or GL initialization. */
class IrisPortalPipelineNativeContractTest {
    private static final String IRIS = "net/irisshaders/iris/Iris";
    private static final String MANAGER = "net/irisshaders/iris/pipeline/PipelineManager";
    private static final String PIPELINE = "net/irisshaders/iris/pipeline/WorldRenderingPipeline";
    private static final String EVENTS = "net/irisshaders/iris/compat/dh/LodRendererEvents";
    private static final String COMPAT = "net/irisshaders/iris/compat/dh/DHCompatInternal";
    private static final String OVERRIDES = "com/seibel/distanthorizons/coreapi/DependencyInjection/OverrideInjector";
    private static final String SCOPE = "qouteall/imm_ptl/core/compat/iris_compatibility/IrisPortalPipelineScope";

    @Test void nativeDhResolutionAndProductionScopeUseTheSameGlobalManager() throws Exception {
        var global = method(read(IRIS), "getPipelineManager");
        assertTrue(instructions(global).anyMatch(i -> i instanceof FieldInsnNode f
            && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals(IRIS) && f.name.equals("pipelineManager")));

        var resolver = method(read(EVENTS), "getInstance");
        assertCall(resolver, IRIS, "getPipelineManager", "()L" + MANAGER + ";");
        assertCall(resolver, MANAGER, "getPipeline", "()Ljava/util/Optional;");
        List<Handle> maps = instructions(resolver).filter(InvokeDynamicInsnNode.class::isInstance)
            .map(InvokeDynamicInsnNode.class::cast).flatMap(i -> Arrays.stream(i.bsmArgs))
            .filter(Handle.class::isInstance).map(Handle.class::cast).toList();
        assertTrue(maps.stream().anyMatch(h -> h.getOwner().equals(PIPELINE) && h.getName().equals("getDHCompat")));
        assertTrue(maps.stream().anyMatch(h -> h.getOwner().equals("net/irisshaders/iris/compat/dh/DHCompat")
            && h.getName().equals("getInstance")));

        assertCall(method(read(SCOPE), "begin"), IRIS, "getPipelineManager", "()L" + MANAGER + ";");
        var adapter = read(SCOPE + "$1");
        assertTrue(adapter.methods.stream().anyMatch(m -> hasCall(m, MANAGER, "getPipelineNullable", "()L" + PIPELINE + ";")));
        assertTrue(adapter.methods.stream().anyMatch(m -> hasCall(m, MANAGER, "preparePipeline",
            "(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)L" + PIPELINE + ";")));
    }

    @Test void nativePreparePublishesBothCreatedAndCachedPipelinesToTheFieldReadByDh() throws Exception {
        var manager = read(MANAGER);
        var prepare = method(manager, "preparePipeline");
        var writes = instructions(prepare).filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
            .filter(f -> f.getOpcode() == Opcodes.PUTFIELD && f.owner.equals(MANAGER) && f.name.equals("pipeline")).toList();
        assertEquals(2, writes.size(), "Both the pipeline factory and cached-dimension branches must publish the global selection");
        assertTrue(calls(prepare).anyMatch(m -> m.owner.equals("java/util/function/Function") && m.name.equals("apply")));
        assertTrue(calls(prepare).anyMatch(m -> m.owner.equals("java/util/Map") && m.name.equals("get")));
        for (String getter : List.of("getPipeline", "getPipelineNullable")) {
            assertTrue(instructions(method(manager, getter)).anyMatch(i -> i instanceof FieldInsnNode f
                && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(MANAGER) && f.name.equals("pipeline")), getter);
        }
        var lastReturn = instructions(prepare).filter(i -> i.getOpcode() == Opcodes.ARETURN).reduce((a, b) -> b).orElseThrow();
        assertInstanceOf(FieldInsnNode.class, previousCode(lastReturn));
        var returnedField = (FieldInsnNode) previousCode(lastReturn);
        assertEquals("pipeline", returnedField.name);
        assertEquals(MANAGER, returnedField.owner);
    }

    @Test void nativeSetupAndRenderCallbacksResolveCurrentCompatBeforeSelectingItsTargets() throws Exception {
        var setup = method(read(EVENTS + "$12"), "beforeSetup");
        var render = method(read(EVENTS + "$13"), "beforeRender");
        for (var callback : List.of(setup, render)) {
            var first = instructions(callback).filter(i -> i.getOpcode() >= 0).findFirst().orElseThrow();
            var resolve = assertInstanceOf(MethodInsnNode.class, first);
            assertEquals(EVENTS, resolve.owner);
            assertEquals("getInstance", resolve.name);
            assertEquals(Opcodes.INVOKESTATIC, resolve.getOpcode());
        }
        assertBindingUsesSelectedCompat(setup, "getSolidFBWrapper", OVERRIDES, "bind");
        assertBindingUsesSelectedCompat(setup, "getGenericShader", OVERRIDES, "bind");
        assertBindingUsesSelectedCompat(render, "getSolidShader", "net/irisshaders/iris/compat/dh/IrisLodRenderProgram", "bind");
        assertBindingUsesSelectedCompat(render, "getTranslucentShader", "net/irisshaders/iris/compat/dh/IrisLodRenderProgram", "bind");
        assertBindingUsesSelectedCompat(render, "getTranslucentFB", "net/irisshaders/iris/gl/framebuffer/GlFramebuffer", "bind");

        for (var constructor : read(COMPAT).methods.stream().filter(m -> m.name.equals("<init>")).toList()) {
            assertFalse(calls(constructor).anyMatch(m -> m.owner.equals(OVERRIDES) && m.name.equals("bind")),
                "Overrides are selected by callbacks, not permanently attached by a pipeline constructor");
        }
    }

    @Test void nativeRenderingFlagAccessorMatchesTheFieldAndScopeGuardsShadersBeforeNativeCapture() throws Exception {
        var nativePipeline = read("net/irisshaders/iris/pipeline/IrisRenderingPipeline");
        assertTrue(nativePipeline.fields.stream().anyMatch(f -> f.name.equals("isRenderingWorld")
            && f.desc.equals("Z") && (f.access & Opcodes.ACC_STATIC) == 0));
        var mixin = read("qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisRenderingPipeline");
        var getter = method(mixin, "ip_getIsRenderingWorld");
        assertEquals("()Z", getter.desc);
        assertTrue(instructions(getter).anyMatch(i -> i instanceof FieldInsnNode f
            && f.getOpcode() == Opcodes.GETFIELD && f.name.equals("isRenderingWorld") && f.desc.equals("Z")));

        var begin = method(read(SCOPE), "begin");
        var guard = calls(begin).filter(m -> m.name.equals("isShaders")).findFirst().orElseThrow();
        var manager = calls(begin).filter(m -> m.owner.equals(IRIS) && m.name.equals("getPipelineManager")).findFirst().orElseThrow();
        int guardIndex = begin.instructions.indexOf(guard), managerIndex = begin.instructions.indexOf(manager);
        assertTrue(guardIndex < managerIndex);
        assertTrue(instructions(begin).anyMatch(i -> i.getOpcode() == Opcodes.ARETURN
            && begin.instructions.indexOf(i) > guardIndex && begin.instructions.indexOf(i) < managerIndex),
            "Shader-off calls must return before selecting a native pipeline or capturing native state");
        var dimension = calls(begin).filter(m -> m.owner.equals(IRIS) && m.name.equals("getCurrentDimension"))
            .findFirst().orElseThrow();
        var normalize = calls(begin).filter(m -> m.owner.equals(MANAGER) && m.name.equals("preparePipeline"))
            .findFirst().orElseThrow();
        var capture = calls(begin).filter(m -> m.name.equals("<init>")
            && m.owner.equals("qouteall/imm_ptl/core/compat/iris_compatibility/IrisSourceRefreshState"))
            .findFirst().orElseThrow();
        assertTrue(begin.instructions.indexOf(dimension) < begin.instructions.indexOf(normalize));
        assertTrue(begin.instructions.indexOf(normalize) < begin.instructions.indexOf(capture),
            "Normalize the manager to Minecraft's current dimension before capturing the outer pipeline state");
    }

    private static void assertBindingUsesSelectedCompat(MethodNode method, String getter, String targetOwner, String targetMethod) {
        assertTrue(calls(method).filter(m -> m.owner.equals(COMPAT) && m.name.equals(getter)).anyMatch(call -> {
            var previous = previousCode(call);
            var next = nextCode(call);
            return previous instanceof VarInsnNode local && local.getOpcode() == Opcodes.ALOAD && local.var == 2
                && next instanceof MethodInsnNode bind && bind.owner.equals(targetOwner) && bind.name.equals(targetMethod);
        }), getter + " must feed the callback's selected compat directly into " + targetMethod);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = IrisPortalPipelineNativeContractTest.class.getResourceAsStream('/' + name + ".class")) {
            assertNotNull(input, "Missing actual dependency class: " + name);
            var type = new ClassNode();
            new ClassReader(input).accept(type, 0);
            return type;
        }
    }

    private static MethodNode method(ClassNode type, String name) {
        return type.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static Stream<AbstractInsnNode> instructions(MethodNode method) {
        return StreamSupport.stream(method.instructions.spliterator(), false);
    }

    private static Stream<MethodInsnNode> calls(MethodNode method) {
        return instructions(method).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast);
    }

    private static boolean hasCall(MethodNode method, String owner, String name, String descriptor) {
        return calls(method).anyMatch(m -> m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(descriptor));
    }

    private static void assertCall(MethodNode method, String owner, String name, String descriptor) {
        assertTrue(hasCall(method, owner, name, descriptor), method.name + " must call " + owner + '.' + name + descriptor);
    }
}
