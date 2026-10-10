package ipl.sable.dim;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contracts against the exact vanilla methods; these do not replace a live Mixin launch. */
class IplHostedPacketHeightTest {
    private static final String HELPER = "ipl/sable/dim/IplHostedPositionHeight";
    private static final String USE = "(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V";
    private static final String BREAK = "(Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;"
        + "Lnet/minecraft/core/Direction;II)V";

    @Test void usePacketGateWrapsTheExistingMaximumBeforeGameModeIsCalled() throws Exception {
        MethodNode vanilla = method(read("net/minecraft/server/network/ServerGamePacketListenerImpl"), "handleUseItemOn", USE);
        assertEquals(1, calls(vanilla, "net/minecraft/world/level/Level", "getMaxBuildHeight"));
        MethodNode hook = methodNamed(read("ipl/sable/mixin/IplHostedPacketHeightMixin"), "ipl$targetStorageMaximum");
        AnnotationNode annotation = annotation(hook, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("handleUseItemOn" + USE), value(annotation, "method"));
        assertEquals(1, value(annotation, "require"));
        assertEquals("INVOKE", value(at(annotation), "value"));
        assertEquals("Lnet/minecraft/world/level/Level;getMaxBuildHeight()I", value(at(annotation), "target"));
        assertEquals(1, calls(hook, HELPER, "maximumForPosition"));
        assertEquals(1, calls(hook, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"),
            "The original maximum remains the ordinary-terrain fallback");
        assertEquals(1, calls(hook, "net/minecraft/world/phys/BlockHitResult", "getBlockPos"));
    }

    @Test void breakingChangesOnlyTheMaximumArgumentSharedByVanillaAndIp() throws Exception {
        assertNotNull(method(read("net/minecraft/server/level/ServerPlayerGameMode"), "handleBlockBreakAction", BREAK));
        MethodNode hook = methodNamed(read("ipl/sable/mixin/IplHostedBreakHeightMixin"), "ipl$breakTargetStorageMaximum");
        AnnotationNode annotation = annotation(hook, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(List.of("handleBlockBreakAction" + BREAK), value(annotation, "method"));
        assertEquals(4, value(annotation, "index"));
        assertEquals(Boolean.TRUE, value(annotation, "argsOnly"));
        assertEquals(1, value(annotation, "require"));
        assertEquals("HEAD", value(at(annotation), "value"));
        assertEquals("(I" + BREAK.substring(1, BREAK.length() - 1) + "I", hook.desc);
        assertEquals(1, calls(hook, HELPER, "maximumForPosition"));
        assertEquals(1, calls(hook, "qouteall/imm_ptl/core/block_manipulation/BlockManipulationServer$Context", "world"),
            "Cross-portal breaks use IP's effective target world");
    }

    @Test void crossPortalAdjacentUpdateUsesThePositionAwareBoundsGate() throws Exception {
        MethodNode remote = methodNamed(read("qouteall/imm_ptl/core/block_manipulation/BlockManipulationServer"), "doProcessUseItemOn");
        assertEquals(1, calls(remote, "net/minecraft/server/level/ServerLevel", "isOutsideBuildHeight"));
        assertEquals(0, calls(remote, "net/minecraft/server/level/ServerLevel", "getMinBuildHeight"));
        assertEquals(0, calls(remote, "net/minecraft/server/level/ServerLevel", "getMaxBuildHeight"));
        assertEquals(1, calls(remote, "net/minecraft/server/level/ServerPlayerGameMode", "useItemOn"));
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = IplHostedPacketHeightTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            ClassNode result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static MethodNode methodNamed(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        return all.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode at(AnnotationNode annotation) {
        Object at = value(annotation, "at");
        return at instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) at;
    }

    private static long calls(MethodNode method, String owner, String name) {
        return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
            .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)).count();
    }
}
