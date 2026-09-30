package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the installed DH cloud-culling method, substituting only camera/config data. */
public class DhPortalCloudCullingTest {
    public static class CloudParams {
        public int instanceOffsetX = 2, instanceOffsetZ = 2, widthInBlocks = 128;
    }
    public static class Entry { public Object get() { return 256; } }
    public static class Settings { public static Entry lodChunkRenderDistanceRadius = new Entry(); }
    public interface Camera {
        DhVec3d getCameraExactPosition();
        DhVec3f getLookAtVector();
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhPortalCloudCullingTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }
    private record Decision(Object instance, Method method) {
        boolean culls(float x, float y, float z, CloudParams params) throws Exception {
            return (boolean) method.invoke(instance, x, y, z, params);
        }
    }
    private static Decision installedDecision(Vector3f look) throws Exception {
        String owner = "com/seibel/distanthorizons/core/render/renderer/CloudRenderHandler";
        var node = new ClassNode();
        try (var stream = DhPortalCloudCullingTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(node, 0);
        }
        node.methods.removeIf(m -> !m.name.equals("shouldCloudBeCulled"));
        assertEquals(1, node.methods.size());
        node.methods.getFirst().access = Opcodes.ACC_PUBLIC;
        node.fields.removeIf(f -> !f.name.equals("MC_RENDER") && !f.name.equals("cullingCorners"));
        node.fields.forEach(f -> f.access = Opcodes.ACC_PUBLIC | (f.access & Opcodes.ACC_STATIC));
        node.innerClasses.clear();
        node.nestMembers = null;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
            owner, "qouteall/imm_ptl/core/compat/dh_compatibility/InstalledCloudCulling",
            owner + "$CloudParams", name(CloudParams.class),
            "com/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper", name(Camera.class),
            "com/seibel/distanthorizons/core/config/Config$Client$Advanced$Graphics$Quality", name(Settings.class),
            "com/seibel/distanthorizons/core/config/types/ConfigEntry", name(Entry.class)
        ))));
        Class<?> type = new Loader().define(writer.toByteArray());
        Object instance = type.getConstructor().newInstance();
        type.getField("cullingCorners").set(instance, new DhVec3d[]{new DhVec3d(), new DhVec3d(), new DhVec3d(), new DhVec3d()});
        type.getField("MC_RENDER").set(null, new Camera() {
            public DhVec3d getCameraExactPosition() { return new DhVec3d(0, 100, 0); }
            public DhVec3f getLookAtVector() { return new DhVec3f(look.x, look.y, look.z); }
        });
        return new Decision(instance, type.getMethod("shouldCloudBeCulled", float.class, float.class, float.class, CloudParams.class));
    }

    @Test void rotatedPortalKeepsDestinationCloudsAndStillCullsBehindTheView() throws Exception {
        var params = new CloudParams();
        var outer = installedDecision(new Vector3f(0, 0, -1));
        var destination = DhPortalCamera.lookDirection(new Matrix4f().rotateY((float)Math.PI));
        var portal = installedDecision(destination);
        assertTrue(outer.culls(0, 400, 1000, params), "Control: source camera culls destination's visible distant cloud");
        assertFalse(portal.culls(0, 400, 1000, params));
        assertTrue(portal.culls(0, 400, -1000, params), "The repair preserves back-facing culling");
        assertTrue(portal.culls(0, 400, 10000, params), "The repair preserves range culling");
        params.instanceOffsetX = params.instanceOffsetZ = 0;
        assertFalse(outer.culls(0, 400, 1000, params), "DH exempts near groups, explaining the partial disappearance");
    }

    @Test void destinationLookUsesFullTransformIncludingTiltMirrorScaleAndIgnoresTranslation() {
        for (Matrix4f transform : new Matrix4f[]{new Matrix4f(), new Matrix4f().rotateY(1.2f).rotateX(.4f),
            new Matrix4f().rotateZ(.6f).scale(-2, 2, 2).rotateY(.9f)}) {
            Vector3f look = DhPortalCamera.lookDirection(transform);
            assertNotNull(look);
            assertEquals(1, look.length(), 1e-6);
            Vector3f inView = transform.transformDirection(new Vector3f(look)).normalize();
            assertTrue(inView.equals(new Vector3f(0, 0, -1), 1e-5f));
            assertTrue(look.equals(DhPortalCamera.lookDirection(new Matrix4f(transform).translate(600, 90, -200)), 1e-5f));
        }
        assertNull(DhPortalCamera.lookDirection(new Matrix4f().zero()));
    }
}
