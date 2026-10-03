package qouteall.imm_ptl.core.sunlight;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Production-bytecode contracts for boundaries that pure ray fixtures cannot exercise. */
class SunlightServerContractTest {
    private static byte[] bytes(String name) throws IOException {
        try (var input = SunlightServerContractTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }
    private static ClassNode node(String name) throws IOException {
        var node = new ClassNode();
        new ClassReader(bytes(name)).accept(node, 0);
        return node;
    }
    @Test void commonAuthorityAndEveryNestedClassLoadWithoutClientOrIrisLinkage() throws Exception {
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("net.irisshaders.")
                    || name.startsWith("com.mojang.blaze3d.")) throw new ClassNotFoundException("Client-only class: " + name);
                if (!name.startsWith("qouteall.imm_ptl.core.sunlight.Sunlight")
                    || name.contains("Client") && !name.endsWith("$ClientSink") || name.contains("Shader")) return super.loadClass(name, resolve);
                Class<?> existing = findLoadedClass(name);
                if (existing != null) return existing;
                try {
                    byte[] data = bytes(name);
                    Class<?> c = defineClass(name, data, 0, data.length);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (IOException e) { throw new ClassNotFoundException(name, e); }
            }
        };
        List<Class<?>> pending = new ArrayList<>();
        for (String name : List.of("SunlightProfile", "SunlightSavedData", "SunlightExposure", "SunlightServer"))
            pending.add(Class.forName("qouteall.imm_ptl.core.sunlight." + name, true, isolated));
        for (int i = 0; i < pending.size(); i++) {
            Class<?> c = pending.get(i);
            c.getDeclaredMethods(); c.getDeclaredFields(); c.getDeclaredConstructors();
            String pool = new String(bytes(c.getName()), StandardCharsets.ISO_8859_1);
            for (String forbidden : List.of("net/minecraft/client/", "net/irisshaders/", "com/mojang/blaze3d/"))
                assertFalse(pool.contains(forbidden), c.getName() + " links " + forbidden);
            pending.addAll(List.of(c.getDeclaredClasses()));
        }
        assertTrue(pending.size() >= 10, "Nested sample, budget, RPC and sink classes must also be inspected");
    }
    @Test void actualPublishRequiresLiveSenderIdentityAndPermissionBeforeAnyMutation() throws Exception {
        var publish = node(SunlightServer.RemoteCallables.class.getName()).methods.stream()
            .filter(m -> m.name.equals("publish")).findFirst().orElseThrow();
        List<AbstractInsnNode> ops = new ArrayList<>();
        for (var insn : publish.instructions) if (insn.getOpcode() >= 0) ops.add(insn);
        int permission = -1, identity = -1, state = -1;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof MethodInsnNode call) {
                if (call.name.equals("hasPermissions")) permission = i;
                if (call.name.equals("state")) state = i;
            }
            if (ops.get(i).getOpcode() == Opcodes.IF_ACMPNE) identity = i;
        }
        assertTrue(identity >= 0 && permission > identity && state > permission);
        assertEquals(Opcodes.ICONST_2, ops.get(permission - 1).getOpcode());
        assertEquals(Opcodes.IFNE, ops.get(permission + 1).getOpcode());
        assertEquals(Opcodes.RETURN, ops.get(permission + 2).getOpcode(), "Permission=false must return before state/mutation");
        var identityGuard = (JumpInsnNode) ops.get(identity);
        AbstractInsnNode rejected = identityGuard.label;
        while (rejected.getOpcode() < 0) rejected = rejected.getNext();
        assertEquals(Opcodes.RETURN, rejected.getOpcode(), "A stale/spoofed sender object must return immediately");
        assertDoesNotThrow(() -> SunlightServer.RemoteCallables.publish(null, 0, "SUN_ANGLE"));
    }
    @Test void loadedChunkReaderCannotForceGenerationOrTickets() throws Exception {
        var authority = node(SunlightServer.class.getName());
        int loadedReads = 0;
        for (var method : authority.methods) for (var insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.name.equals("getChunkNow")) loadedReads++;
            assertFalse(List.of("getChunk", "getChunkAt", "addRegionTicket", "setChunkForced").contains(call.name),
                method.name + " unexpectedly requests world data using " + call.name);
        }
        assertEquals(1, loadedReads);
    }
    @Test void malformedSyncCannotActivateClientAndValidProfilePublishesImmutableValue() {
        var accepted = new ArrayList<SunlightProfile>();
        SunlightServer.setClientSink((revision, profile) -> accepted.add(profile));
        try {
            SunlightServer.RemoteCallables.accept(0, true, 20, "SUN_ANGLE");
            SunlightServer.RemoteCallables.accept(1, true, Double.NaN, "SUN_ANGLE");
            SunlightServer.RemoteCallables.accept(1, true, 20, "invalid");
            assertTrue(accepted.isEmpty());
            SunlightServer.RemoteCallables.accept(7, true, 20, "SUN_ANGLE");
            assertEquals(List.of(new SunlightProfile(true, 20, SunlightProfile.Clock.SUN_ANGLE)), accepted);
        } finally { SunlightServer.setClientSink(null); }
    }
}
