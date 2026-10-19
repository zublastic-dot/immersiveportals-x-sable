package qouteall.imm_ptl.core.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ShaderCompilationMixinContractTest {
    private static final String HOOK = "qouteall/imm_ptl/core/mixin/client/render/shader/MixinProgram";

    @Test void redirectCapturesActualMojangArgumentsWithoutReplacingMethodOrLocals() throws IOException {
        var target = compiled("com/mojang/blaze3d/shaders/Program").methods.stream()
            .filter(method -> method.name.equals("compileShaderInternal")).findFirst().orElseThrow();
        var hook = compiled(HOOK);
        var redirect = hook.methods.stream().filter(method -> method.name.equals("redirectReadShaderSource"))
            .findFirst().orElseThrow();
        assertTrue((target.access & Opcodes.ACC_STATIC) != 0);
        assertTrue((redirect.access & Opcodes.ACC_STATIC) != 0);
        Type[] expectedArguments = Stream.concat(
            Stream.of(Type.getType(java.io.InputStream.class), Type.getType(java.nio.charset.Charset.class)),
            Arrays.stream(Type.getArgumentTypes(target.desc))
        ).toArray(Type[]::new);
        assertArrayEquals(expectedArguments, Type.getArgumentTypes(redirect.desc),
            "Mixin Redirect capture must append the enclosing method's exact arguments");
        assertEquals(Type.getType(String.class), Type.getReturnType(redirect.desc));
        var annotation = redirect.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Redirect;"))
            .findFirst().orElseThrow();
        assertEquals(List.of("compileShaderInternal"), value(annotation, "method"));
        var at = (AnnotationNode) value(annotation, "at");
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;",
            value(at, "target"));
        assertEquals(false, value(at, "remap"));
        assertEquals(1, Arrays.stream(target.instructions.toArray()).filter(instruction ->
            instruction instanceof MethodInsnNode call && call.owner.equals("org/apache/commons/io/IOUtils")
                && call.name.equals("toString") && call.desc.equals("(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;")).count());
    }

    @Test void shaderIdentityHasNoPersistentStateOrExceptionalReturnCleanupDependency() throws IOException {
        var hook = compiled(HOOK);
        for (var field : hook.fields) {
            assertFalse(field.desc.contains("ThreadLocal"), field.name);
            assertFalse(field.name.equals("ip_programType") || field.name.equals("ip_programName"));
        }
        for (var method : hook.methods) {
            if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations) {
                assertFalse(annotation.desc.endsWith("/Inject;") || annotation.desc.endsWith("/Overwrite;")
                    || annotation.desc.endsWith("/WrapMethod;"), "Keep Iris's target body/local captures intact");
            }
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("java/lang/ThreadLocal", call.owner);
            }
        }
    }

    private static ClassNode compiled(String name) throws IOException {
        try (var input = ShaderCompilationMixinContractTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
}
