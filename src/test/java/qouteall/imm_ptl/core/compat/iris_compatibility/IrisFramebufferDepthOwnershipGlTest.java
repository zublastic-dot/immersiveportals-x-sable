package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.objectweb.asm.Opcodes.*;

/** Runs installed Iris's resize method bytecode against real GPU depth attachments.
 * Only its framebuffer boundary is substituted; the native branch/iteration logic
 * is preserved. The repaired variant applies the same ownership guard as the mixin.
 */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
public class IrisFramebufferDepthOwnershipGlTest {
    private static long window;
    private static final IrisFramebufferDepthOwnership<GpuFramebuffer> ownership = new IrisFramebufferDepthOwnership<>();
    private final List<Integer> textures = new ArrayList<>();
    private final List<GpuFramebuffer> framebuffers = new ArrayList<>();

    @BeforeAll static void open() {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(16, 16, "Iris DH depth ownership regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll static void close() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @AfterEach void cleanup() {
        ownership.clear();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        framebuffers.forEach(fb -> glDeleteFramebuffers(fb.id));
        textures.forEach(id -> glDeleteTextures(id));
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test void nativeResizeReproducesStaleSourceDepthAndGuardPreservesActualDhWrites() throws Exception {
        for (boolean repaired : new boolean[]{false, true}) {
            int dhDepth = texture(), oldMain = texture(), newMain = texture();
            var terrain = framebuffer(dhDepth);
            var water = framebuffer(dhDepth);
            var generic = framebuffer(dhDepth);
            var vanilla = framebuffer(oldMain);
            for (var fb : List.of(terrain, water, generic)) ownership.external(fb);
            terrain.clear(.25); // stale source-dimension contents
            var recipient = framebuffer(newMain);
            recipient.clear(.75);

            Object nativeResize = resizeHarness(repaired);
            set(nativeResize, "cachedWidth", 8);
            set(nativeResize, "cachedHeight", 8);
            set(nativeResize, "currentDepthFormat", DepthBufferFormat.DEPTH32F);
            set(nativeResize, "ownedFramebuffers", new ArrayList<>(List.of(terrain, water, generic, vanilla)));
            var method = java.util.Arrays.stream(nativeResize.getClass().getMethods())
                .filter(m -> m.getName().equals("resizeIfNeeded")).findFirst().orElseThrow();
            assertEquals(false, method.invoke(nativeResize, 1, newMain, 8, 8, DepthBufferFormat.DEPTH32F, null));

            assertEquals(newMain, vanilla.depthAttachment(), "Vanilla must still follow the replacement main target");
            for (var fb : List.of(terrain, water, generic))
                assertEquals(repaired ? dhDepth : newMain, fb.depthAttachment());
            terrain.clear(.9); // Iris clears and renders destination DH through this FBO
            var sampledDh = framebuffer(dhDepth);
            assertEquals(repaired ? .9f : .25f, sampledDh.depthPixel(), 1e-6f,
                "The shader's unchanged DH sampler must see destination depth, not stale source depth");
            assertEquals(repaired ? .75f : .9f, recipient.depthPixel(), 1e-6f,
                "Drawing DH must not overwrite vanilla depth");
        }
    }

    @Test void nativeDhTextureReplacementRemainsPossibleAfterMainTargetSwap() throws Exception {
        int first = texture(), replacement = texture();
        var dh = framebuffer(first);
        ownership.external(dh);
        ownership.updateMainDepth(dh, texture(), GpuFramebuffer::addDepthAttachment);
        // The repair only wraps RenderTargets.resizeIfNeeded, not native DH reconnect.
        dh.addDepthAttachment(replacement);
        dh.clear(.6);
        assertEquals(replacement, dh.depthAttachment());
        assertEquals(.6f, dh.depthPixel(), 1e-6f);
    }

    public static void guardedAttach(GpuFramebuffer fb, int texture) {
        ownership.updateMainDepth(fb, texture, GpuFramebuffer::addDepthAttachment);
    }

    private static Object resizeHarness(boolean guarded) throws Exception {
        var source = IrisFramebufferDepthOwnershipTest.read("net/irisshaders/iris/targets/RenderTargets");
        String generated = "qouteall/imm_ptl/core/compat/iris_compatibility/NativeDepthResize" + guarded;
        String gpu = Type.getInternalName(GpuFramebuffer.class);
        var remapper = new SimpleRemapper(Map.of(source.name, generated,
            "net/irisshaders/iris/gl/framebuffer/GlFramebuffer", gpu));
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, generated, null, "java/lang/Object", null);
        for (var field : source.fields)
            if ((field.access & ACC_STATIC) == 0)
                writer.visitField(ACC_PUBLIC, field.name, remapper.mapDesc(field.desc), null, null).visitEnd();
        var constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        var method = source.methods.stream().filter(m -> m.name.equals("resizeIfNeeded")).findFirst().orElseThrow();
        if (guarded) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("addDepthAttachment")) {
                    call.setOpcode(INVOKESTATIC);
                    call.owner = Type.getInternalName(IrisFramebufferDepthOwnershipGlTest.class);
                    call.name = "guardedAttach";
                    call.desc = "(L" + gpu + ";I)V";
                    call.itf = false;
                }
            }
        }
        method.accept(new MethodRemapper(writer.visitMethod(ACC_PUBLIC, method.name,
            remapper.mapMethodDesc(method.desc), null, null), remapper));
        writer.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(IrisFramebufferDepthOwnershipGlTest.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().define(writer.toByteArray()).getConstructor().newInstance();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }

    private int texture() {
        int texture = glGenTextures(); textures.add(texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, 8, 8, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private GpuFramebuffer framebuffer(int depth) {
        var framebuffer = new GpuFramebuffer(); framebuffers.add(framebuffer);
        framebuffer.addDepthAttachment(depth);
        return framebuffer;
    }

    public static class GpuFramebuffer {
        final int id = glGenFramebuffers();
        public boolean hasDepthAttachment() { return true; }
        public void addDepthAttachment(int texture) {
            glBindFramebuffer(GL_FRAMEBUFFER, id);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        }
        int depthAttachment() {
            glBindFramebuffer(GL_FRAMEBUFFER, id);
            return glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }
        void clear(double depth) {
            glBindFramebuffer(GL_FRAMEBUFFER, id);
            glDisable(GL_SCISSOR_TEST); glDepthMask(true);
            glClearDepth(depth); glClear(GL_DEPTH_BUFFER_BIT);
        }
        float depthPixel() {
            glBindFramebuffer(GL_FRAMEBUFFER, id);
            float[] value = new float[1];
            glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, value);
            return value[0];
        }
    }
}
