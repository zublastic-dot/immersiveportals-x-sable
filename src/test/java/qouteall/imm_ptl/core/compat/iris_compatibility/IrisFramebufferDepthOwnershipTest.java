package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrisFramebufferDepthOwnershipTest {
    @Test void ownershipUsesIdentityAndExpiresWithFramebufferOrPipeline() {
        var owner = new IrisFramebufferDepthOwnership<Object>();
        Object dh = new String("equal"), vanilla = new String("equal");
        var attached = new ArrayList<Object>();
        owner.external(dh);
        owner.external(null);
        owner.updateMainDepth(dh, 7, (fb, texture) -> attached.add(fb));
        owner.updateMainDepth(vanilla, 7, (fb, texture) -> attached.add(fb));
        assertEquals(1, attached.size());
        assertSame(vanilla, attached.getFirst());
        owner.remove(dh);
        owner.updateMainDepth(dh, 8, (fb, texture) -> attached.add(fb));
        assertSame(dh, attached.getLast());
        owner.external(dh);
        owner.clear();
        owner.updateMainDepth(dh, 9, (fb, texture) -> attached.add(fb));
        assertEquals(3, attached.size());
    }

    @Test void installedIrisOwnsDhObjectsButResizeTreatsTheirDepthAsMainDepth() throws IOException {
        var targets = read("net/irisshaders/iris/targets/RenderTargets");
        var create = targets.methods.stream().filter(m -> m.name.equals("createDHFramebuffer")).findFirst().orElseThrow();
        assertEquals("(Lcom/google/common/collect/ImmutableSet;[I)Lnet/irisshaders/iris/gl/framebuffer/GlFramebuffer;", create.desc);
        assertEquals(List.of("createEmptyFramebuffer", "invert", "createColorFramebuffer"), calls(create.instructions));
        for (String name : List.of("createEmptyFramebuffer", "createColorFramebuffer")) {
            var method = targets.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            assertTrue(calls(method.instructions).contains("add"), "DH FBO remains in native ownedFramebuffers: " + name);
        }
        var resize = targets.methods.stream().filter(m -> m.name.equals("resizeIfNeeded")).findFirst().orElseThrow();
        assertEquals("(IIIILnet/irisshaders/iris/gl/texture/DepthBufferFormat;Lnet/irisshaders/iris/shaderpack/properties/PackDirectives;)Z", resize.desc);
        assertEquals(1, calls(resize.instructions).stream().filter("addDepthAttachment"::equals).count());
        assertTrue(calls(resize.instructions).contains("hasDepthAttachment"));
        for (String name : List.of("destroyFramebuffer", "destroy"))
            assertTrue(targets.methods.stream().anyMatch(m -> m.name.equals(name)));
    }

    @Test void installedDhReconnectStillOwnsTerrainWaterAndGenericDepth() throws IOException {
        var compat = read("net/irisshaders/iris/compat/dh/DHCompatInternal");
        var reconnect = compat.methods.stream().filter(m -> m.name.equals("reconnectDHTextures")).findFirst().orElseThrow();
        assertEquals("(I)V", reconnect.desc);
        assertEquals(3, calls(reconnect.instructions).stream().filter("addDepthAttachment"::equals).count());
        var mixin = read("qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisDhDepthOwnership");
        assertTrue(mixin.fields.stream().anyMatch(f -> f.name.equals("ip_dhDepthOwnership") && (f.access & Opcodes.ACC_STATIC) == 0));
        try (var input = getClass().getResourceAsStream("/imm_ptl_compat.mixins.json")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("iris.MixinIrisDhDepthOwnership"));
        }
    }

    static ClassNode read(String name) throws IOException {
        try (var input = IrisFramebufferDepthOwnershipTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static List<String> calls(org.objectweb.asm.tree.InsnList instructions) {
        var calls = new ArrayList<String>();
        for (var instruction : instructions)
            if (instruction instanceof MethodInsnNode call) calls.add(call.name);
        return calls;
    }
}
