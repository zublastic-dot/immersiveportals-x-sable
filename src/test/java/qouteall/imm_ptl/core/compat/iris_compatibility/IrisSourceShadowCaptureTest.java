package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.shadows.ShadowRenderer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import qouteall.imm_ptl.core.compat.mixin.iris.MixinIrisSourceShadowCapture;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bytecode contract: plain JUnit does not apply Mixins or run a Minecraft shadow pass. */
class IrisSourceShadowCaptureTest {
    @Test void actualIrisEarlyReturnCannotRefreshTheSnapshot() throws Exception {
        var target = read(ShadowRenderer.class);
        var method = target.methods.stream().filter(m -> m.name.equals("renderShadows")).findFirst().orElseThrow();
        int returns = 0, firstReturn = -1, copy = -1, lastReturn = -1;
        for (int i = 0; i < method.instructions.size(); i++) {
            var instruction = method.instructions.get(i);
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (firstReturn < 0) firstReturn = i;
                returns++; lastReturn = i;
            }
            if (instruction instanceof MethodInsnNode call && call.name.equals("copyPreTranslucentDepth")) copy = i;
        }
        assertTrue(returns >= 2, "Iris has an early return when shadows are disabled");
        assertTrue(firstReturn < copy && copy < lastReturn, "Only normal completion follows the real opaque-depth copy");
        var hook = read(MixinIrisSourceShadowCapture.class).methods.stream()
            .filter(m -> m.name.equals("ip_captureSourceShadow")).findFirst().orElseThrow();
        var inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("renderShadows"), value(inject, "method"));
        @SuppressWarnings("unchecked") var at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("TAIL", value(at.getFirst(), "value"), "RETURN would also capture stale data after the disabled branch");
    }

    @Test void targetFieldsOpaqueDepthAndReleaseHookMatchActualIris() throws Exception {
        var target = read(ShadowRenderer.class); var mixin = read(MixinIrisSourceShadowCapture.class);
        for (String name : List.of("pipeline", "targets", "halfPlaneLength", "renderDistanceMultiplier")) {
            var declaration = mixin.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
            var original = target.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
            assertEquals(original.desc, declaration.desc); assertNotEquals(0, original.access & Opcodes.ACC_FINAL);
        }
        var capture = mixin.methods.stream().filter(m -> m.name.equals("ip_captureSourceShadow")).findFirst().orElseThrow();
        boolean opaque = false, ownedCopy = false;
        for (var instruction : capture.instructions) if (instruction instanceof MethodInsnNode call) {
            opaque |= call.name.equals("getDepthTextureNoTranslucents");
            ownedCopy |= call.owner.endsWith("/PortalSourceShadow") && call.name.equals("capture");
        }
        assertTrue(opaque); assertTrue(ownedCopy);
        var destroy = mixin.methods.stream().filter(m -> m.name.equals("ip_releaseSourceShadow")).findFirst().orElseThrow();
        var inject = destroy.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("destroy"), value(inject, "method"));
        assertTrue(target.methods.stream().anyMatch(m -> m.name.equals("destroy") && m.desc.equals("()V")));
        try (var stream = getClass().getResourceAsStream("/imm_ptl_compat.mixins.json")) {
            assertNotNull(stream);
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("iris.MixinIrisSourceShadowCapture"));
        }
    }

    private static Object value(AnnotationNode annotation, String key) { return annotation.values.get(annotation.values.indexOf(key) + 1); }
    private static ClassNode read(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream); var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
}
