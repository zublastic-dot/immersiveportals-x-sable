package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import qouteall.imm_ptl.core.mixin.client.render.shader.MixinProgram;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL20.*;

/** Exercise the real source redirect, not a parallel implementation of its lifecycle. */
class ShaderCompilationContextTest {
    private Field configs;
    private Object previousConfigs;
    private Method redirect;

    @BeforeEach void prepareTransformations() throws ReflectiveOperationException {
        configs = ShaderCodeTransformation.class.getDeclaredField("configs");
        configs.setAccessible(true);
        previousConfigs = configs.get(null);
        configs.set(null, List.of(
            config(ShaderCodeTransformation.ShaderType.fs, "fragment", "TOKEN", "FRAGMENT"),
            config(ShaderCodeTransformation.ShaderType.vs, "vertex", "TOKEN", "VERTEX"),
            config(ShaderCodeTransformation.ShaderType.fs, "invalid", "[", "unused")
        ));
        redirect = MixinProgram.class.getDeclaredMethod("redirectReadShaderSource",
            InputStream.class, Charset.class, Program.Type.class, String.class,
            InputStream.class, String.class, GlslPreprocessor.class);
        redirect.setAccessible(true);
    }

    @AfterEach void restoreTransformations() throws IllegalAccessException {
        configs.set(null, previousConfigs);
    }

    @Test void readFailurePreservesOriginalExceptionAndNextShaderStillTransforms() throws Throwable {
        IOException failure = new IOException("broken source stream");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
        };
        assertSame(failure, assertThrows(IOException.class, () -> read(broken, Program.Type.FRAGMENT, "broken")));
        assertEquals("FRAGMENT", read(text("TOKEN"), Program.Type.FRAGMENT, "fragment"));
    }

    @Test void transformationFailureDoesNotPoisonNextShader() throws Throwable {
        assertThrows(PatternSyntaxException.class, () -> read(text("TOKEN"), Program.Type.FRAGMENT, "invalid"));
        assertEquals("VERTEX", read(text("TOKEN"), Program.Type.VERTEX, "vertex"));
    }

    @Test void compilerFailureAfterReadingSourceDoesNotLeaveCompilationState() throws Throwable {
        IOException compileFailure = new IOException("original GLSL compiler diagnostic");
        assertSame(compileFailure, assertThrows(IOException.class, () -> {
            assertEquals("FRAGMENT", read(text("TOKEN"), Program.Type.FRAGMENT, "fragment"));
            // Compilation happens after this redirect returns in Mojang's method.
            throw compileFailure;
        }));
        assertEquals("VERTEX", read(text("TOKEN"), Program.Type.VERTEX, "vertex"));
    }

    @Test void errorIsNotSwallowedAndNextShaderStillWorks() throws Throwable {
        AssertionError failure = new AssertionError("source provider failed");
        InputStream broken = new InputStream() {
            @Override public int read() { throw failure; }
        };
        assertSame(failure, assertThrows(AssertionError.class, () -> read(broken, Program.Type.FRAGMENT, "broken")));
        assertEquals("FRAGMENT", read(text("TOKEN"), Program.Type.FRAGMENT, "fragment"));
    }

    @Test void nestedReadFailureCannotReplaceOuterShaderIdentity() throws Throwable {
        InputStream nested = new ByteArrayInputStream("TOKEN".getBytes(StandardCharsets.UTF_8)) {
            private boolean called;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (!called) {
                    called = true;
                    assertThrows(PatternSyntaxException.class,
                        () -> ShaderCompilationContextTest.this.read(text("TOKEN"), Program.Type.FRAGMENT, "invalid"));
                    try {
                        assertEquals("VERTEX", ShaderCompilationContextTest.this.read(text("TOKEN"), Program.Type.VERTEX, "vertex"));
                    } catch (Throwable error) { throw new AssertionError(error); }
                }
                return super.read(bytes, offset, length);
            }
        };
        assertEquals("FRAGMENT", read(nested, Program.Type.FRAGMENT, "fragment"));
    }

    @Test void concurrentReadersKeepTheirOwnShaderArguments() throws Exception {
        CyclicBarrier rendezvous = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fragment = executor.submit(() -> concurrentRead(rendezvous, Program.Type.FRAGMENT, "fragment"));
            var vertex = executor.submit(() -> concurrentRead(rendezvous, Program.Type.VERTEX, "vertex"));
            assertEquals("FRAGMENT", fragment.get(15, TimeUnit.SECONDS));
            assertEquals("VERTEX", vertex.get(15, TimeUnit.SECONDS));
        }
    }

    @Test void transformationStillUsesBothTypeAndShaderName() throws Throwable {
        assertEquals("FRAGMENT", read(text("TOKEN"), Program.Type.FRAGMENT, "fragment"));
        assertEquals("TOKEN", read(text("TOKEN"), Program.Type.VERTEX, "fragment"));
        assertEquals("TOKEN", read(text("TOKEN"), Program.Type.FRAGMENT, "unlisted"));
    }

    @Test
    @EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
    void realGlCompilerRejectsReservedWordThenCompilesNextShaderThroughProductionRedirect() throws Throwable {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(8, 8, "Shader exception recovery regression", 0, 0);
        try {
            assertNotEquals(0, window);
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            String broken = "#version 150\nout vec4 color;\nvoid main() { int packed = 1; color = vec4(packed); }";
            IOException diagnostic = assertThrows(IOException.class, () -> compileGl(broken, "broken-gl"));
            assertFalse(diagnostic.getMessage().isBlank(), "Original compiler diagnostic must remain visible");
            compileGl(broken.replace("packed", "tileData"), "valid-gl");
        } finally {
            GL.setCapabilities(null);
            glfwMakeContextCurrent(0);
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private void compileGl(String source, String name) throws Throwable {
        int shader = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(shader, read(text(source), Program.Type.FRAGMENT, name));
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IOException(glGetShaderInfoLog(shader));
        } finally { glDeleteShader(shader); }
    }

    private String concurrentRead(CyclicBarrier barrier, Program.Type type, String name) {
        InputStream input = new ByteArrayInputStream("TOKEN".getBytes(StandardCharsets.UTF_8)) {
            private boolean waited;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (!waited) {
                    waited = true;
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (Exception error) { throw new AssertionError(error); }
                }
                return super.read(bytes, offset, length);
            }
        };
        try { return read(input, type, name); }
        catch (Throwable error) { throw new AssertionError(error); }
    }

    private String read(InputStream input, Program.Type type, String name) throws Throwable {
        try {
            return (String) redirect.invoke(null, input, StandardCharsets.UTF_8, type, name,
                input, "test-pack", null);
        } catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static InputStream text(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ShaderCodeTransformation.Config config(
        ShaderCodeTransformation.ShaderType type, String name, String pattern, String replacement
    ) {
        var entry = new ShaderCodeTransformation.TransformationEntry();
        entry.pattern = pattern;
        entry.replacement = replacement;
        var config = new ShaderCodeTransformation.Config();
        config.type = type;
        config.affectedShaders = Set.of(name);
        config.transformations = List.of(entry);
        return config;
    }
}
