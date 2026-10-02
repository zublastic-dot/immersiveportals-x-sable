package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.VarInsnNode;
import qouteall.imm_ptl.core.compat.mixin.iris.MixinIrisPortalShaderPreprocessor;
import qouteall.imm_ptl.core.lighting.PortalShaderPackAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the compiled production wrapper around Iris's real preprocessor.
 * The plain JUnit launch does not apply Mixins; the annotation/target bytecode
 * contract is checked separately from the call-frame and observation behavior. */
class IrisPortalShaderPreprocessorTest {
    private static final String PACK = "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";
    private static final List<StringPair> DEFINES = List.of(new StringPair("ROTATION", "-37.5"));
    private Field configField;
    private Object previousConfig;
    private Method wrapper;

    @BeforeEach void setup() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        configField = Iris.class.getDeclaredField("irisConfig");
        configField.setAccessible(true);
        previousConfig = configField.get(null);
        var config = new IrisConfig(Path.of("unused-observer-iris.properties"), Path.of("unused-observer-exclusions.json"));
        config.setShaderPackName(PACK);
        configField.set(null, config);
        wrapper = MixinIrisPortalShaderPreprocessor.class.getDeclaredMethod(
            "ip_observeSourceOptions", String.class, Iterable.class, Operation.class);
        wrapper.setAccessible(true);
        PortalShaderPackAdapter.clear();
    }

    @AfterEach void cleanup() throws Exception {
        PortalShaderPackAdapter.clear();
        configField.set(null, previousConfig);
    }

    private String preprocess(String source) throws Exception {
        var calls = new AtomicInteger();
        Operation<String> original = args -> {
            calls.incrementAndGet();
            assertSame(source, args[0]);
            assertSame(DEFINES, args[1]);
            return JcppProcessor.glslPreprocessSource((String) args[0], DEFINES);
        };
        String result = (String) wrapper.invoke(null, source, DEFINES, original);
        assertEquals(1, calls.get(), "Preprocessing must run exactly once");
        assertEquals(JcppProcessor.glslPreprocessSource(source, DEFINES), result,
            "Observing options must not change Iris's output");
        return result;
    }

    private static String source(String dimension, String expression) {
        String variable = expression.startsWith("fract(") ? "tAmin" : "timeAngle";
        return "#version 330\n#define " + dimension + "\n"
            + "const float sunPathRotation = ROTATION;\n"
            + "void main() { float " + variable + " = " + expression + "; }\n";
    }

    @Test void rewrittenIrisArgumentDoesNotLoseOriginalDimensionAdmission() throws Exception {
        String input = source("OVERWORLD", "worldTimeSmooth / 24000.0");
        String result = preprocess(input);
        assertFalse(result.contains("#define OVERWORLD"), "The actual JCPP output strips dimension directives");
        assertFalse(result.contains("ROTATION"), "The observer must read option-expanded output");
        assertEquals(-37.5, PortalShaderPackAdapter.sunPathRotationDegrees().orElseThrow());
        assertEquals(PortalShaderPackAdapter.ClockMode.WORLD_TIME, PortalShaderPackAdapter.clockMode().orElseThrow());
    }

    @Test void netherCompilationAlsoPublishesSourceClockAndReloadCanChangeIt() throws Exception {
        preprocess(source("NETHER", "worldTimeSmooth / 24000.0"));
        assertEquals(PortalShaderPackAdapter.ClockMode.WORLD_TIME, PortalShaderPackAdapter.clockMode().orElseThrow());
        PortalShaderPackAdapter.clear();
        preprocess(source("NETHER", "fract(sunAngle - 0.033333333)"));
        assertEquals(-37.5, PortalShaderPackAdapter.sunPathRotationDegrees().orElseThrow());
        assertEquals(PortalShaderPackAdapter.ClockMode.SUN_ANGLE, PortalShaderPackAdapter.clockMode().orElseThrow());
    }

    @Test void endCompilationCannotPublishOrOverwriteOverworldDirection() throws Exception {
        String end = source("END", "worldTimeSmooth / 24000.0").replace("ROTATION", "83.0");
        preprocess(end);
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
        assertTrue(PortalShaderPackAdapter.clockMode().isEmpty());
        preprocess(source("OVERWORLD", "fract(sunAngle - 0.033333333)"));
        preprocess(end);
        assertEquals(-37.5, PortalShaderPackAdapter.sunPathRotationDegrees().orElseThrow());
        assertEquals(PortalShaderPackAdapter.ClockMode.SUN_ANGLE, PortalShaderPackAdapter.clockMode().orElseThrow());
    }

    @Test void missingConfigUnknownPackAndUnclassifiedInputRemainUnobserved() throws Exception {
        String input = source("OVERWORLD", "worldTimeSmooth / 24000.0");
        Object configured = configField.get(null);
        configField.set(null, null);
        preprocess(input);
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
        configField.set(null, configured);
        Iris.getIrisConfig().setShaderPackName("unverified shader pack");
        preprocess(input);
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
        Iris.getIrisConfig().setShaderPackName(PACK);
        preprocess(input.replace("#define OVERWORLD\n", ""));
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
    }

    @Test void originalFailuresPropagateWithoutPublishingPartialState() {
        var failure = new IllegalStateException("upstream preprocessing failed");
        Operation<String> original = args -> { throw failure; };
        var thrown = assertThrows(InvocationTargetException.class,
            () -> wrapper.invoke(null, source("OVERWORLD", "worldTimeSmooth / 24000.0"), DEFINES, original));
        assertSame(failure, thrown.getCause());
        assertTrue(PortalShaderPackAdapter.sunPathRotationDegrees().isEmpty());
    }

    @Test void installedIrisReassignsSourceAndProductionHookWrapsItsCallFrame() throws Exception {
        var iris = read(JcppProcessor.class);
        var target = iris.methods.stream().filter(m -> m.name.equals("glslPreprocessSource"))
            .findFirst().orElseThrow();
        var returned = target.instructions.getLast();
        while (returned.getOpcode() < 0) returned = returned.getPrevious();
        assertEquals(Opcodes.ARETURN, returned.getOpcode());
        var load = (VarInsnNode) returned.getPrevious();
        assertEquals(Opcodes.ALOAD, load.getOpcode());assertEquals(0, load.var);
        var stored = (VarInsnNode) load.getPrevious();
        assertEquals(Opcodes.ASTORE, stored.getOpcode());assertEquals(0, stored.var,
            "The installed Iris target overwrites source with its output before RETURN");

        var hook = read(MixinIrisPortalShaderPreprocessor.class).methods.stream()
            .filter(m -> m.name.equals(wrapper.getName())).findFirst().orElseThrow();
        var annotation = hook.visibleAnnotations.stream()
            .filter(a -> a.desc.endsWith("/WrapMethod;")).findFirst().orElseThrow();
        assertEquals(List.of("glslPreprocessSource"), annotation.values.get(annotation.values.indexOf("method") + 1));
        assertEquals(1, annotation.values.get(annotation.values.indexOf("require") + 1));
    }

    private static ClassNode read(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);var node = new ClassNode();new ClassReader(stream).accept(node,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);return node;
        }
    }
}
