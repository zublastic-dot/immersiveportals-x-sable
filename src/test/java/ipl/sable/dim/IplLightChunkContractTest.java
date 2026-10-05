package ipl.sable.dim;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring contracts for paths that ordinary JVM tests cannot Mixin-transform. */
class IplLightChunkContractTest {
    private static final String HELPER = "ipl/sable/dim/IplLightChunkOwnership";

    private static ClassNode read(String path) throws Exception {
        try (var in = IplLightChunkContractTest.class.getResourceAsStream("/" + path + ".class")) {
            assertNotNull(in, path);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        var result = new ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static MethodNode injection(ClassNode node, String target, String point) {
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                Object targets = value(annotation, "method");
                if (!(targets instanceof List<?> list) || list.stream().noneMatch(target::equals)) continue;
                assertEquals(1, value(annotation, "require"), "An absent guard must fail rather than silently disappear");
                Object ats = value(annotation, "at");
                assertInstanceOf(List.class, ats);
                assertTrue(((List<?>) ats).stream().anyMatch(at -> at instanceof AnnotationNode a
                    && point.equals(value(a, "value"))), target + " must intercept at " + point);
                if (!target.equals("<init>")) assertEquals(Boolean.TRUE, value(annotation, "cancellable"));
                return method;
            }
        }
        return fail("Missing " + target + " injection on " + node.name);
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    @Test void vanillaLightingLookupUsesVirtualNonCreatingChunkAccess() throws Exception {
        var node = read("net/minecraft/world/level/chunk/ChunkSource");
        var method = node.methods.stream().filter(m -> m.name.equals("getChunkForLighting")).findFirst().orElseThrow();
        MethodInsnNode lookup = null;
        boolean emptyStatus = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getChunk")) lookup = call;
            if (instruction instanceof FieldInsnNode field && field.name.equals("EMPTY")) emptyStatus = true;
        }
        assertNotNull(lookup);
        assertTrue(emptyStatus);
        assertEquals(Opcodes.INVOKEVIRTUAL, lookup.getOpcode(), "IP's map override must remain covered");
        assertEquals(Opcodes.ICONST_0, lookup.getPrevious().getOpcode(), "Lighting must not request missing chunks");
    }

    @Test void productionLevelChunkFilterUsesActualOwnerAndTheTestedIdentitySelector() throws Exception {
        var node = read(HELPER);
        var filter = node.methods.stream().filter(m -> m.name.equals("forWorld")).findFirst().orElseThrow();
        assertTrue(calls(filter, "net/minecraft/world/level/chunk/LevelChunk", "getLevel"));
        assertTrue(calls(filter, HELPER, "select"));
        assertTrue(calls(filter, "net/minecraft/world/level/chunk/ChunkAccess", "getPos"));
        assertTrue(calls(filter, HELPER, "selectPosition"));
        assertTrue(java.util.stream.StreamSupport.stream(filter.instructions.spliterator(), false)
            .anyMatch(i -> i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                && type.desc.equals("net/minecraft/world/level/chunk/EmptyLevelChunk")),
            "Render-only empty placeholders must not masquerade as loaded light chunks");
        for (var instruction : filter.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(List.of("getHeight", "getMinBuildHeight", "dimension").contains(call.name),
                    "Matching height or dimension keys must never substitute for owner identity");
            }
        }
    }

    @Test void inheritedLightingLookupFiltersReturnedChunksWithoutChangingRenderLookup() throws Exception {
        var node = read("ipl/sable/mixin/IplLightChunkGetterMixin");
        var guard = injection(node, "getChunkForLighting", "RETURN");
        assertTrue(calls(guard, HELPER, "forWorld"));
        assertTrue(calls(guard, "net/minecraft/world/level/chunk/LightChunkGetter", "getLevel"));
        for (var method : node.methods) for (var annotation : annotations(method)) {
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                assertEquals(List.of("getChunkForLighting"), value(annotation, "method"),
                    "Ordinary render and interaction getChunk routes must remain intact");
            }
        }
    }

    @Test void immediateScalableLuxLookupFiltersByEngineOwner() throws Exception {
        var guard = injection(read("ipl/sable/mixin/compat/IplStarLightChunkLookupMixin"), "getAnyChunkNow", "RETURN");
        assertTrue(calls(guard, HELPER, "forWorld"));
        assertTrue(java.util.stream.StreamSupport.stream(guard.instructions.spliterator(), false)
            .anyMatch(i -> i instanceof FieldInsnNode field && field.name.equals("world")));
    }

    @Test void packetLightMasksAreRejectedBeforeUsingTheWrongSectionOrigin() throws Exception {
        var guard = injection(read("ipl/sable/mixin/client/IplClientPacketLightingMixin"), "enableChunkLight", "HEAD");
        assertTrue(calls(guard, HELPER, "belongsTo"));
        assertTrue(calls(guard, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel"));
    }

    @Test void scalableClientArrayUploadCapturesEngineOwnerAndRejectsForeignChunks() throws Exception {
        var node = read("ipl/sable/mixin/compat/IplScalableLuxClientChunkMixin");
        var capture = injection(node, "<init>", "RETURN");
        assertTrue(calls(capture, "net/minecraft/world/level/chunk/LightChunkGetter", "getLevel"));
        var guard = injection(node, "scalablelux$clientChunkLoad", "HEAD");
        assertTrue(calls(guard, HELPER, "belongsTo"));
        assertTrue(calls(guard, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel"));
    }
}
