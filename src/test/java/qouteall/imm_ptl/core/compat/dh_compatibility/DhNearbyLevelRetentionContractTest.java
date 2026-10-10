package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DhNearbyLevelRetentionContractTest {
    private static final String WRAPPER = "com/seibel/distanthorizons/common/wrappers/world/ClientLevelWrapper_neoforge";
    private static final String HELPER = "qouteall/imm_ptl/core/compat/dh_compatibility/DhNearbyLevelRetention";
    private static final String HOOK = "qouteall/imm_ptl/core/compat/mixin/dh/MixinDhNearbyLevelRetention";

    @Test void dependencyCleanupAbiHasExactlyOneTimerUnloadAndSeparateReplacementUnload() throws IOException {
        checkNative(compiled(WRAPPER));
    }

    @Test void bothInspectedSupportedJarsHaveTheSameNarrowHookContract() throws IOException {
        String older = System.getenv("IP_PORTAL_TEST_DH_332_JAR");
        String newer = System.getenv("IP_PORTAL_TEST_DH_333_JAR");
        assumeTrue(older != null && newer != null, "Supply both inspected DH JAR paths for release validation");
        for (var entry : java.util.Map.of("3.3.2", older, "3.3.3", newer).entrySet()) {
            try (var jar = new JarFile(Path.of(entry.getValue()).toFile())) {
                try (var metadata = jar.getInputStream(jar.getJarEntry("META-INF/neoforge.mods.toml"))) {
                    String text = new String(metadata.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(text.contains("version=\"" + entry.getKey() + "\"")
                        || text.matches("(?s).*version\\s*=\\s*\"" + java.util.regex.Pattern.quote(entry.getKey()) + "\".*"));
                }
                try (var input = jar.getInputStream(jar.getJarEntry(WRAPPER + ".class"))) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, 0); checkNative(node);
                }
            }
        }
    }

    private static void checkNative(ClassNode wrapper) {
        var cleanup = method(wrapper, "tickCleanup", "()V");
        assertTrue((cleanup.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(1, calls(cleanup, WRAPPER, "tryUnloadFromWorld"));
        assertEquals(2, calls(cleanup, WRAPPER, "getLastAccessTime"));
        assertTrue(java.util.Arrays.stream(cleanup.instructions.toArray()).anyMatch(instruction ->
            instruction instanceof LdcInsnNode value && Long.valueOf(30000L).equals(value.cst)));
        assertEquals(1, wrapper.methods.stream().filter(m -> !m.name.equals("tickCleanup"))
            .mapToInt(m -> calls(m, WRAPPER, "tryUnloadFromWorld")).sum(), "Server-key replacement must remain native");
        assertTrue((method(wrapper, "tryUnloadFromWorld", "()V").access & Opcodes.ACC_PRIVATE) != 0);
        var getter = method(wrapper, "getWrappedMcObject", "()Lnet/minecraft/client/multiplayer/ClientLevel;");
        assertEquals(0, java.util.Arrays.stream(getter.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).count());
        assertEquals(1, java.util.Arrays.stream(getter.instructions.toArray()).filter(i ->
            i instanceof FieldInsnNode field && field.name.equals("level") && field.getOpcode() == Opcodes.GETFIELD).count());
        assertTrue(wrapper.fields.stream().anyMatch(field -> field.name.equals("level") && (field.access & Opcodes.ACC_FINAL) != 0));
        var dhGetter = method(wrapper, "getDhLevel", "()Lcom/seibel/distanthorizons/core/level/IDhLevel;");
        assertEquals(0, java.util.Arrays.stream(dhGetter.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).count());
    }

    @Test void mixinWrapsOnlyTimerSiteAndPreservesOriginalForIneligibleOrClosedWrappers() throws IOException {
        var hook = compiled(HOOK);
        var wrapper = method(hook, "ip_keepNearbyDestination", "(L" + WRAPPER
            + ";Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V");
        assertTrue((wrapper.access & Opcodes.ACC_STATIC) != 0);
        var annotation = wrapper.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(List.of("tickCleanup()V"), value(annotation, "method"));
        assertEquals(1, value(annotation, "require"));
        var at = (List<?>) value(annotation, "at");
        assertEquals(1, at.size());
        assertEquals("L" + WRAPPER + ";tryUnloadFromWorld()V", value((AnnotationNode) at.getFirst(), "target"));
        assertEquals(1, calls(wrapper, WRAPPER, "getDhLevel"));
        assertEquals(1, calls(wrapper, WRAPPER, "getWrappedMcObject"));
        assertEquals(1, calls(wrapper, HELPER, "shouldRetain"));
        assertEquals(1, calls(wrapper, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"));
        assertEquals(1, hook.methods.stream().filter(m -> m.visibleAnnotations != null)
            .flatMap(m -> m.visibleAnnotations.stream()).filter(a -> a.desc.endsWith("/WrapOperation;")).count());
    }

    @Test void publicationUsesLoadedWorldsAndOpeningGeometryWithoutCreatingOrTickingAnything() throws IOException {
        var helper = compiled(HELPER);
        var refresh = method(helper, "refresh", "()V");
        assertEquals(1, calls(refresh, "qouteall/imm_ptl/core/ClientWorldLoader", "getClientWorlds"));
        assertEquals(1, calls(refresh, "net/minecraft/client/multiplayer/ClientLevel", "entitiesForRendering"));
        assertEquals(1, calls(refresh, "qouteall/imm_ptl/core/portal/global_portals/GlobalPortalStorage", "getGlobalPortals"));
        assertTrue(helper.methods.stream().anyMatch(m -> calls(m, "qouteall/imm_ptl/core/portal/Portal", "getDistanceToNearestPointInPortal") == 1));
        for (var m : helper.methods) for (var instruction : m.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("com/seibel/distanthorizons/"), "Optional boundary must not initialize DH");
                assertFalse(List.of("getWorld", "getOrLoadLevel", "getWrapper", "getChunk", "clientTick", "markAccessed").contains(call.name));
            }
        }
        for (var instruction : refresh.instructions) if (instruction instanceof FieldInsnNode field) {
            assertFalse(field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("level"),
                "Use actual player world, not render-swapped Minecraft.level");
        }
        var timer = method(helper, "shouldRetain", "(Lnet/minecraft/client/multiplayer/ClientLevel;)Z");
        for (var instruction : timer.instructions) if (instruction instanceof MethodInsnNode call) {
            assertTrue(call.owner.equals("java/lang/System") && call.name.equals("nanoTime")
                || call.owner.equals(HELPER + "$Policy") && call.name.equals("retains"));
        }
    }

    @Test void registrationIsClientOnlyAndSnapshotStoresNoStrongWorldOrSessionReference() throws IOException {
        var init = method(compiled("qouteall/imm_ptl/core/IPModMainClient"), "init", "()V");
        assertEquals(1, calls(init, HELPER, "init"));
        try (var input = getClass().getResourceAsStream("/imm_ptl_compat.mixins.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.substring(json.indexOf("\"client\"")).contains("dh.MixinDhNearbyLevelRetention"));
        }
        var snapshot = compiled(HELPER + "$Snapshot");
        for (var field : snapshot.fields) {
            assertTrue(List.of("Ljava/lang/ref/WeakReference;", "Ljava/util/List;", "J").contains(field.desc), field.name);
            assertTrue((field.access & Opcodes.ACC_FINAL) != 0);
        }
        var policy = compiled(HELPER + "$Policy");
        assertTrue(policy.fields.stream().anyMatch(f -> f.name.equals("snapshot") && (f.access & Opcodes.ACC_VOLATILE) != 0));
    }

    private static ClassNode compiled(String owner) throws IOException {
        try (var input = DhNearbyLevelRetentionContractTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input, owner);
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
}
