package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the compiled native begin/close path; only external runtime dependencies are substituted. */
public class IrisPortalPipelineNativeScopeTest {
    private static final String SOURCE = "qouteall/imm_ptl/core/compat/iris_compatibility/IrisPortalPipelineScope";
    private static final String ISOLATED = "test/ExecutableNativeIrisPipelineScope";
    private Class<?> scopeType;

    public interface Pipeline {}

    public static final class PhasePipeline implements Pipeline, IEIrisNewWorldRenderingPipeline {
        public boolean renderingWorld;
        public int phaseWrites;
        @Override public boolean ip_getIsRenderingWorld() { return renderingWorld; }
        @Override public void ip_setIsRenderingWorld(boolean value) {
            renderingWorld = value;
            phaseWrites++;
        }
    }

    public static final class Manager {
        public final PhasePipeline a = new PhasePipeline(), b = new PhasePipeline();
        public final Map<String, Pipeline> pipelines = Map.of("A", a, "B", b);
        public final List<String> prepared = new ArrayList<>();
        public Pipeline selected = a;
        public Pipeline getPipelineNullable() { return selected; }
        public Pipeline preparePipeline(String dimension) {
            prepared.add(dimension);
            return selected = pipelines.get(dimension);
        }
    }

    public static final class FakeIris {
        public static Manager manager;
        public static String dimension;
        public static int managerReads;
        public static Manager getPipelineManager() { managerReads++; return manager; }
        public static String getCurrentDimension() { return dimension; }
    }

    public static final class Level {}

    public static final class FakeMinecraft {
        public static FakeMinecraft instance;
        public static int reads;
        public Level level = new Level();
        public static FakeMinecraft getInstance() { reads++; return instance; }
    }

    public static final class Invoker {
        public boolean shaders = true;
        public boolean isShaders() { return shaders; }
    }

    public static final class FakeIrisInterface {
        public static Invoker invoker;
    }

    public static final class CapturedState {
        public static int captures, restores;
        public static String current;
        private final String saved;
        public CapturedState() { captures++; saved = current; }
        public void close() { restores++; current = saved; }
    }

    @BeforeEach void resetDependenciesAndLoadActualScope() throws Exception {
        FakeIris.manager = new Manager();
        FakeIris.dimension = "A";
        FakeIris.managerReads = 0;
        FakeMinecraft.instance = new FakeMinecraft();
        FakeMinecraft.reads = 0;
        FakeIrisInterface.invoker = new Invoker();
        CapturedState.captures = 0;
        CapturedState.restores = 0;
        CapturedState.current = "outer camera/fog";
        scopeType = new IsolatedScopeLoader().loadClass(ISOLATED.replace('/', '.'));
    }

    @Test void nativeBeginRestoresSameDimensionParentPhaseAfterChildClearsIt() throws Exception {
        var parent = FakeIris.manager.a;
        parent.renderingWorld = true;
        try (var scope = begin()) {
            assertNotNull(scope);
            assertEquals(1, CapturedState.captures);
            parent.renderingWorld = false;
            CapturedState.current = "same dimension child camera/fog";
        }

        assertSame(parent, FakeIris.manager.selected);
        assertTrue(parent.renderingWorld, "The same pipeline object still needs its outer phase restored");
        assertEquals(1, parent.phaseWrites);
        assertEquals("outer camera/fog", CapturedState.current);
        assertEquals(1, CapturedState.restores);
        assertEquals(List.of("A", "A"), FakeIris.manager.prepared);
    }

    @Test void nativeBeginNormalizesOuterDimensionAndCloseEndsChildPhaseBeforeRestoringParent() throws Exception {
        var manager = FakeIris.manager;
        manager.a.renderingWorld = true;
        manager.selected = manager.b; // Auxiliary work can precede the primary setup after a crossing.
        try (var scope = begin()) {
            assertNotNull(scope);
            assertSame(manager.a, manager.selected);
            FakeIris.dimension = "B";
            manager.preparePipeline("B");
            manager.b.renderingWorld = true;
            manager.a.renderingWorld = false;
            CapturedState.current = "different dimension child camera/fog";
            FakeIris.dimension = "A"; // The world wrapper restores Minecraft first.
        }

        assertSame(manager.a, manager.selected);
        assertTrue(manager.a.renderingWorld);
        assertFalse(manager.b.renderingWorld);
        assertEquals(1, manager.a.phaseWrites);
        assertEquals(1, manager.b.phaseWrites);
        assertEquals("outer camera/fog", CapturedState.current);
        assertEquals(1, CapturedState.restores);
        assertEquals(List.of("A", "B", "A"), manager.prepared);
    }

    @Test void shaderOffReturnsBeforeMinecraftManagerOrCapturedStateAccess() throws Exception {
        FakeIrisInterface.invoker.shaders = false;
        assertNull(begin());
        assertEquals(0, FakeMinecraft.reads);
        assertEquals(0, FakeIris.managerReads);
        assertTrue(FakeIris.manager.prepared.isEmpty());
        assertEquals(0, CapturedState.captures);
        assertEquals(0, CapturedState.restores);
    }

    private AutoCloseable begin() throws Exception {
        return (AutoCloseable) scopeType.getMethod("begin").invoke(null);
    }

    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }

    private static final class IsolatedScopeLoader extends ClassLoader {
        private final Map<String, String> mappings = new HashMap<>();

        IsolatedScopeLoader() {
            super(IrisPortalPipelineNativeScopeTest.class.getClassLoader());
            for (String suffix : List.of("", "$Selection", "$1")) mappings.put(SOURCE + suffix, ISOLATED + suffix);
            mappings.put("net/irisshaders/iris/Iris", name(FakeIris.class));
            mappings.put("net/irisshaders/iris/pipeline/PipelineManager", name(Manager.class));
            mappings.put("net/irisshaders/iris/pipeline/WorldRenderingPipeline", name(Pipeline.class));
            mappings.put("net/irisshaders/iris/shaderpack/materialmap/NamespacedId", "java/lang/String");
            mappings.put("net/minecraft/client/Minecraft", name(FakeMinecraft.class));
            mappings.put("net/minecraft/client/multiplayer/ClientLevel", name(Level.class));
            mappings.put("qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface", name(FakeIrisInterface.class));
            mappings.put("qouteall/imm_ptl/core/compat/iris_compatibility/IrisInterface$Invoker", name(Invoker.class));
            mappings.put("qouteall/imm_ptl/core/compat/iris_compatibility/IrisSourceRefreshState", name(CapturedState.class));
        }

        @Override protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
            String internalName = binaryName.replace('.', '/');
            if (!internalName.equals(ISOLATED) && !internalName.startsWith(ISOLATED + '$'))
                throw new ClassNotFoundException(binaryName);
            String original = SOURCE + internalName.substring(ISOLATED.length());
            try (var input = getParent().getResourceAsStream(original + ".class")) {
                if (input == null) throw new ClassNotFoundException("Missing compiled production scope: " + original);
                var writer = new ClassWriter(0);
                new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(mappings)), 0);
                byte[] bytecode = writer.toByteArray();
                return defineClass(binaryName, bytecode, 0, bytecode.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException(binaryName, failure);
            }
        }
    }
}
