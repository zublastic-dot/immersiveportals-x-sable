package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Execute Iris's actual sampling method with the compiled production argument hook.
 * Only the live Minecraft/entity/level boundaries are replaced. This is not a
 * transformed Minecraft launch or visual fog acceptance test. */
public class IrisPortalEyeBrightnessTest {
    public static class Entity {
        public Vec3 feet = new Vec3(-11.239, 45.87802, 51.519);
        public Vec3 position() { return feet; }
        public double getEyeY() { return feet.y + 1.62; }
    }

    public static class Level {
        public final List<BlockPos> sampled = new ArrayList<>();
        public int getBrightness(LightLayer layer, BlockPos position) {
            sampled.add(position);
            // Synthetic destination: underground at the source coordinates,
            // open sky at the transformed portal camera in the owner's example.
            return layer == LightLayer.BLOCK ? 7 : position.getY() >= 100 ? 15 : 0;
        }
    }

    public static class Client {
        public Entity cameraEntity = new Entity();
        public Level level = new Level();
    }

    private record Sampler(Class<?> type, Client client) {
        Vector2i sample() throws Exception {
            return (Vector2i) type.getMethod("getEyeBrightness").invoke(null);
        }
    }

    private static class Loader extends ClassLoader {
        Loader() { super(IrisPortalEyeBrightnessTest.class.getClassLoader()); }
        Class<?> define(byte[] code) { return defineClass(null, code, 0, code.length); }
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = IrisPortalEyeBrightnessTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }

    private static Sampler sampler(boolean patched) throws Exception {
        String iris = "net/irisshaders/iris/uniforms/CommonUniforms";
        String generated = "qouteall/imm_ptl/core/compat/iris_compatibility/ExtractedIrisEyeBrightness";
        var upstream = read(iris);
        var method = upstream.methods.stream().filter(m -> m.name.equals("getEyeBrightness")
            && m.desc.equals("()Lorg/joml/Vector2i;")).findFirst().orElseThrow();
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;

        var node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = generated;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "client",
            "Lnet/minecraft/client/Minecraft;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ZERO_VECTOR_2i",
            "Lorg/joml/Vector2i;", null, null));
        node.methods.add(method);

        var mixin = read("qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisPortalEyeBrightness");
        var hook = mixin.methods.stream().filter(m -> m.name.equals("ip_destinationEyePosition"))
            .findFirst().orElseThrow();
        node.methods.add(hook);
        var annotation = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/ModifyArg;"))
            .findFirst().orElseThrow();
        var at = (AnnotationNode) annotation.values.get(annotation.values.indexOf("at") + 1);
        String target = (String) at.values.get(at.values.indexOf("target") + 1);
        assertEquals(List.of("getEyeBrightness"), annotation.values.get(annotation.values.indexOf("method") + 1));
        assertEquals(0, annotation.values.get(annotation.values.indexOf("index") + 1));

        int matches = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                && target.equals("L" + call.owner + ";" + call.name + call.desc)) {
                matches++;
                if (patched) method.instructions.insertBefore(call,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, generated, hook.name, hook.desc, false));
            }
        }
        assertEquals(1, matches, "Exactly one installed Iris position conversion must match the real hook");
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
            iris, generated,
            "net/minecraft/client/Minecraft", internal(Client.class),
            "net/minecraft/client/multiplayer/ClientLevel", internal(Level.class),
            "net/minecraft/world/entity/Entity", internal(Entity.class)
        ))));
        Class<?> type = new Loader().define(writer.toByteArray());
        var client = new Client();
        type.getField("client").set(null, client);
        type.getField("ZERO_VECTOR_2i").set(null, new Vector2i());
        return new Sampler(type, client);
    }

    private static void push(Vec3 position) throws Exception {
        // No level is used by the render-info position accessors under test.
        var constructor = WorldRenderInfo.class.getDeclaredConstructor(ClientLevel.class, Vec3.class,
            Matrix4f.class, boolean.class, UUID.class, int.class, boolean.class, boolean.class,
            boolean.class, boolean.class, WorldRenderInfo.IsometricParameters.class);
        constructor.setAccessible(true);
        WorldRenderInfo.pushRenderInfo(constructor.newInstance(null, position, null, true,
            null, 6, false, false, true, true, null));
    }

    @AfterEach void clearRenderStack() {
        while (WorldRenderInfo.isRendering()) WorldRenderInfo.popRenderInfo();
    }

    @Test void sourceCoordinatesSuppressSkylightButDestinationCameraRestoresIt() throws Exception {
        var original = sampler(false);
        var repaired = sampler(true);
        Vec3 destination = new Vec3(-7.050, 108.50994, 48.160);
        push(destination);
        assertEquals(new Vector2i(112, 0), original.sample());
        assertEquals(new Vector2i(112, 240), repaired.sample());
        assertEquals(List.of(new BlockPos(-8, 108, 48), new BlockPos(-8, 108, 48)),
            repaired.client.level.sampled);
        assertEquals(new Vec3(-11.239, 45.87802, 51.519), repaired.client.cameraEntity.feet);
    }

    @Test void mainViewStillUsesTheOriginalEntityEyes() throws Exception {
        var original = sampler(false);
        var repaired = sampler(true);
        for (Vec3 feet : List.of(new Vec3(-11.239, 45.87802, 51.519), new Vec3(7.7, 105.2, -0.01))) {
            original.client.cameraEntity.feet = feet;
            repaired.client.cameraEntity.feet = feet;
            assertEquals(original.sample(), repaired.sample());
            assertEquals(original.client.level.sampled, repaired.client.level.sampled);
        }
    }

    @Test void cameraCoordinatesAreFlooredOnceWithoutAddingAnotherEyeHeight() throws Exception {
        var repaired = sampler(true);
        push(new Vec3(-0.001, 99.999, -16.001));
        assertEquals(new Vector2i(112, 0), repaired.sample());
        assertEquals(List.of(new BlockPos(-1, 99, -17), new BlockPos(-1, 99, -17)),
            repaired.client.level.sampled);
    }

    @Test void nestedAndMovingViewsUseTheirCurrentCameraAndUnwindToTheParent() throws Exception {
        var repaired = sampler(true);
        var parent = new Vec3(200.1, 120.8, -400.2);
        push(parent);
        assertEquals(240, repaired.sample().y);
        push(new Vec3(20481032.25, 61.5, 20493080.75));
        try { assertEquals(0, repaired.sample().y); }
        finally { WorldRenderInfo.popRenderInfo(); }
        assertEquals(240, repaired.sample().y);
        assertEquals(BlockPos.containing(parent), repaired.client.level.sampled.getLast());
        WorldRenderInfo.popRenderInfo();
        push(new Vec3(-500.4, 101.1, 900.9));
        try { assertEquals(240, repaired.sample().y); }
        finally { WorldRenderInfo.popRenderInfo(); }
        assertEquals(0, repaired.sample().y);
        assertEquals(new BlockPos(-12, 47, 51), repaired.client.level.sampled.getLast());
    }

    @Test void irisNullWorldAndEntityGuardsRemainIntact() throws Exception {
        var repaired = sampler(true);
        WorldRenderInfo.pushRenderInfo(null); // A position read here would fail.
        repaired.client.level = null;
        assertEquals(new Vector2i(), repaired.sample());
        repaired.client.level = new Level();
        repaired.client.cameraEntity = null;
        assertEquals(new Vector2i(), repaired.sample());
        assertTrue(repaired.client.level.sampled.isEmpty());
    }

    @Test void absentOrUnverifiedIrisVersionsDoNotLoadTheHook() {
        assertTrue(IrisPortalUniformCompatibility.supports("1.8.14-beta.1+mc1.21.1"));
        assertFalse(IrisPortalUniformCompatibility.supports(null));
        assertFalse(IrisPortalUniformCompatibility.supports("1.8.13+mc1.21.1"));
        assertFalse(IrisPortalUniformCompatibility.supports("1.9.0+mc1.21.1"));
    }
}
