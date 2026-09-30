package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the compiled production upload with only live Iris/GL boundaries replaced. */
public class DhTextureSamplingPolicyTest {
    public static class Program {
        public int location = 17, lookups;
        public final List<Boolean> uploads = new ArrayList<>();
        public int tryGetUniformLocation(CharSequence name) {
            assertEquals("uIpContinuousTextureGradients", name.toString());
            lookups++;
            return location;
        }
        public void setUniform(int location, boolean enabled) {
            assertEquals(this.location, location);
            uploads.add(enabled);
        }
    }
    public static class Iris {
        public static Invoker invoker = new Invoker();
    }
    public static class Invoker {
        public boolean shaders;
        public boolean isShaders() { return shaders; }
    }
    public static class Portal {
        public static boolean portal;
        public static boolean isSupportedPass() { return portal; }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhTextureSamplingPolicyTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }

    private static Program loadUpload() throws Exception {
        var node = new ClassNode();
        try (var stream = DhTextureSamplingPolicyTest.class.getResourceAsStream(
            "/qouteall/imm_ptl/core/compat/mixin/dh/MixinDhTerrainTextures.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(node, 0);
        }
        node.superName = name(Program.class);
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")
                    && call.owner.equals("java/lang/Object")) call.owner = name(Program.class);
            }
        }
        var remap = Map.of(
            "com/seibel/distanthorizons/common/render/openGl/glObject/shader/GlShaderProgram", name(Program.class),
            "qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface", name(Iris.class),
            "qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface$Invoker", name(Invoker.class),
            "qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering", name(Portal.class));
        var writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(remap)));
        return (Program) new Loader().define(writer.toByteArray()).getConstructor().newInstance();
    }

    private static void upload(Program program) throws Exception {
        var method = program.getClass().getDeclaredMethod("ip_uploadTexturePolicy", DhApiRenderParam.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(program, new DhApiRenderParam(), new CallbackInfo("test", false));
    }

    @Test void portalAndDirectViewsShareSamplingAndShaderToggleResetsTheUniform() throws Exception {
        Program program = loadUpload();
        Iris.invoker.shaders = false;
        Portal.portal = true;
        upload(program);
        Portal.portal = false; // Same scene immediately after crossing.
        upload(program);
        Iris.invoker.shaders = true;
        upload(program);
        Portal.portal = true;
        upload(program);
        Iris.invoker.shaders = false;
        upload(program);
        assertEquals(List.of(true, true, false, false, true), program.uploads);
        assertEquals(1, program.lookups);
    }

    @Test void missingUniformLeavesAnUnrecognizedProgramAlone() throws Exception {
        Program program = loadUpload();
        program.location = -1;
        upload(program);
        upload(program);
        assertTrue(program.uploads.isEmpty());
        assertEquals(1, program.lookups);
    }
}
