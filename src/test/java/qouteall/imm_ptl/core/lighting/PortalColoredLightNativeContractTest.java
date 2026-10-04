package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

/** Inspect the actual optional Colorful jar without starting its Minecraft singleton engine. */
class PortalColoredLightNativeContractTest {
    private static final String ROOT = "me/erykczy/colorfullighting/";
    private static final String LEVEL = "L" + ROOT + "common/accessors/LevelAccessor;";
    private static final String STATE = "L" + ROOT + "common/accessors/BlockStateAccessor;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String RGB = "L" + ROOT + "common/util/ColorRGB4;";

    @Test void installedPublicApiSupportsAnExplicitSourceWorldAndNativeConfiguration() throws IOException {
        method(node("dev/colorfullighting/compat/CompatGates"), "engineEnabled", "()Z");
        method(node(ROOT + "accessors/LevelWrapper"), "<init>",
            "(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/renderer/LevelRenderer;)V");
        method(node(ROOT + "accessors/BlockStateWrapper"), "<init>", "(Lnet/minecraft/world/level/block/state/BlockState;)V");
        var config = node(ROOT + "common/Config");
        method(config, "getColorEmission", "(" + LEVEL + POS + STATE + ")" + RGB);
        method(config, "getColoredLightTransmittance", "(" + LEVEL + POS + STATE + ")" + RGB);
        for (String channel : new String[]{"red4", "green4", "blue4"}) assertTrue(node(ROOT + "common/util/ColorRGB4").fields.stream()
            .anyMatch(f -> f.name.equals(channel) && f.desc.equals("I") && (f.access & Opcodes.ACC_PUBLIC) != 0));
    }
    @Test void wrappedEmissionAndOpacityConsultTheWrappedLevelRatherThanGlobalClientLevel() throws IOException {
        var wrapper = node(ROOT + "accessors/BlockStateWrapper");
        for (String name : new String[]{"getLightEmission", "getLightBlock"}) {
            var method = method(wrapper, name, "(" + LEVEL + POS + ")I");
            boolean explicit = false;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                explicit |= call.owner.equals(ROOT + "accessors/LevelWrapper") && call.name.equals("getWrappedLevel");
                assertFalse(call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance"));
            }
            assertTrue(explicit, name + " must honor LevelWrapper(source, null)");
        }
    }
    @Test void nativeEmissionIncludesUserBrightnessOverrideAndFilterReturnsChannelCaps() throws IOException {
        var config = node(ROOT + "common/Config");
        var emission = method(config, "getColorEmission", "(" + LEVEL + POS + STATE + ")" + RGB);
        boolean override = false, configuredEmitter = false;
        for (var instruction : emission.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("overriddenBrightness4")) override = true;
            if (instruction instanceof MethodInsnNode call && call.owner.equals(ROOT + "common/Config") && call.name.equals("findEmitter")) configuredEmitter = true;
        }
        assertTrue(override && configuredEmitter, "Vanilla-dark custom definitions require Config's own emitter resolver");
        var filter = method(config, "getColoredLightTransmittance", "(" + LEVEL + POS + STATE + ")" + RGB);
        boolean nativeCap = false;
        for (var instruction : filter.instructions) if (instruction instanceof FieldInsnNode field)
            nativeCap |= field.name.equals("transmittance") && field.desc.equals(RGB);
        assertTrue(nativeCap, "Propagation must cap each channel with native filter transmittance");
    }
    @Test void snapshotStateCachingIsLimitedToNativePositionIndependentInputs() throws IOException {
        var wrapper = node(ROOT + "accessors/BlockStateWrapper");
        assertEquals(1, wrapper.fields.size(), "Caching wrappers is safe only while they carry no query/world state");
        var field = wrapper.fields.getFirst();
        assertEquals("Lnet/minecraft/world/level/block/state/BlockState;", field.desc);
        assertTrue((field.access & Opcodes.ACC_FINAL) != 0);
        var config = node(ROOT + "common/Config");
        var filter = method(config, "getColoredLightTransmittance", "(" + LEVEL + POS + STATE + ")" + RGB);
        for (var instruction : filter.instructions) if (instruction instanceof VarInsnNode local)
            assertFalse(local.getOpcode() == Opcodes.ALOAD && local.var < 2,
                "Snapshot filter caching must not discard native level/position inputs");
        var emission = method(config, "getColorEmission", "(" + LEVEL + POS + STATE + ")" + RGB);
        boolean callsPositionDependentEmission = false;
        for (var instruction : emission.instructions) if (instruction instanceof MethodInsnNode call)
            callsPositionDependentEmission |= call.name.equals("getLightEmission") && call.desc.equals("(" + LEVEL + POS + ")I");
        assertTrue(callsPositionDependentEmission, "Emission results must remain uncached per position");
    }
    private static ClassNode node(String name) throws IOException {
        String path = System.getProperty("colorfulJar", "");
        if (path.isBlank()) path = System.getenv("IP_PORTAL_TEST_COLORFUL_JAR");
        Assumptions.assumeTrue(path != null && !path.isBlank(),
            "Provide colorfulJar or IP_PORTAL_TEST_COLORFUL_JAR for installed Colorful ABI coverage");
        assertTrue(Files.isRegularFile(Path.of(path)), "Configured Colorful ABI input must exist: " + path);
        try (var jar = new JarFile(path)) {
            var entry = jar.getJarEntry(name + ".class"); assertNotNull(entry, name);
            try (var input = jar.getInputStream(entry)) { var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node; }
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        var method = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError(node.name + "." + name + descriptor));
        assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0); return method;
    }
}
