package ipl.sable.natives;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks compiled wiring and resolved Sable targets without initializing Minecraft or JNI. */
class IplNativeHookContractTest {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String RAPIER = "dev/ryanhcode/sable/physics/impl/rapier/Rapier3D";

    @Test void nativeLoaderCannotSilentlyMissOrMultiplyItsTarget() throws Exception {
        MethodNode target = onlyMethod(read(RAPIER), "loadLibrary");
        assertEquals("()V", target.desc);
        assertNotEquals(0, target.access & Opcodes.ACC_STATIC);
        assertRequiredHead("IplNativesOverrideMixin", "loadLibrary");
    }

    @Test void fusedDriverCannotSilentlyLeaveStockPerDimensionSteppingEnabled() throws Exception {
        MethodNode target = onlyMethod(read("dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem"),
            "tickPipelinePhysics");
        assertEquals("(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V", target.desc);
        assertEquals(0, target.access & Opcodes.ACC_STATIC);
        assertRequiredHead("IplFusedStepMixin", "tickPipelinePhysics");
    }

    @Test void stepGateRequiresExactlyTheSingleNativeWorldStepInSable() throws Exception {
        MethodNode target = onlyMethod(read("dev/ryanhcode/sable/physics/impl/rapier/RapierPhysicsPipeline"),
            "physicsTick");
        assertEquals("(D)V", target.desc);
        int worldSteps = 0;
        for (var instruction : target.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(RAPIER)
                && call.name.equals("step") && call.desc.equals("(JD)V")) {
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                worldSteps++;
            }
        }
        assertEquals(1, worldSteps, "A changed Sable step structure must be reviewed before release");

        AnnotationNode hook = requiredHook("IplRapierStepArmMixin", WRAP, "physicsTick");
        AnnotationNode point = onlyPoint(hook);
        assertEquals("INVOKE", value(point, "value"));
        assertEquals("L" + RAPIER + ";step(JD)V", value(point, "target"));
        assertNull(value(point, "ordinal"), "Do not hide duplicate native steps with an ordinal filter");
        assertNull(value(hook, "slice"), "The gate must account for all native step calls");
    }

    private static void assertRequiredHead(String mixin, String method) throws Exception {
        AnnotationNode hook = requiredHook(mixin, INJECT, method);
        assertEquals(Boolean.TRUE, value(hook, "cancellable"));
        assertEquals("HEAD", value(onlyPoint(hook), "value"));
    }

    private static AnnotationNode requiredHook(String mixin, String descriptor, String method) throws Exception {
        List<AnnotationNode> matches = new ArrayList<>();
        for (MethodNode candidate : read("ipl/sable/mixin/" + mixin).methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (candidate.visibleAnnotations != null) annotations.addAll(candidate.visibleAnnotations);
            if (candidate.invisibleAnnotations != null) annotations.addAll(candidate.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(descriptor)
                    && List.of(method).equals(value(annotation, "method"))) matches.add(annotation);
            }
        }
        assertEquals(1, matches.size(), mixin + " must have exactly one required hook");
        AnnotationNode hook = matches.getFirst();
        assertEquals(1, value(hook, "require"), "Atlas cannot run with a missing physics hook");
        assertEquals(1, value(hook, "allow"), "Atlas cannot run with a duplicated physics hook");
        assertEquals(Boolean.FALSE, value(hook, "remap"));
        return hook;
    }

    private static AnnotationNode onlyPoint(AnnotationNode hook) {
        List<?> points = assertInstanceOf(List.class, value(hook, "at"));
        assertEquals(1, points.size());
        return assertInstanceOf(AnnotationNode.class, points.getFirst());
    }

    private static MethodNode onlyMethod(ClassNode owner, String name) {
        List<MethodNode> methods = owner.methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner.name + "." + name + " must remain an unambiguous target");
        return methods.getFirst();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String path) throws Exception {
        try (var in = IplNativeHookContractTest.class.getResourceAsStream("/" + path + ".class")) {
            assertNotNull(in, path);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
