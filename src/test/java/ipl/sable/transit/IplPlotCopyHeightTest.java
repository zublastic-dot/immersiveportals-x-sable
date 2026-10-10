package ipl.sable.transit;

import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises production preflight bytecode with chunk fixtures, without launching Minecraft. */
public class IplPlotCopyHeightTest {
    private static final String HELPER = "ipl/sable/dim/IplPlotCopyHeight";
    private static final LevelHeightAccessor FULL_STORAGE = LevelHeightAccessor.create(-2032, 4064);

    public record State(boolean air) {
        public boolean isAir() { return air; }
    }

    public static final class Section {
        private final State[] states = new State[4096];
        private boolean empty = true;

        Section() { Arrays.fill(states, new State(true)); }
        public boolean hasOnlyAir() { return empty; }
        public State getBlockState(int x, int y, int z) { return states[(y * 16 + x) * 16 + z]; }
        void at(int y) {
            states[(y * 16 + 15) * 16 + 15] = new State(false);
            empty = false;
        }
    }

    public static final class Chunk {
        private final int minSection;
        private final Section[] sections;
        private int count;
        private Runnable onRead = () -> {};

        Chunk(int minY, int height) {
            minSection = minY >> 4;
            count = height / 16;
            sections = new Section[count];
            Arrays.setAll(sections, ignored -> new Section());
        }
        public Section[] getSections() { onRead.run(); return sections; }
        public int getSectionsCount() { return count; }
        public int getSectionYFromSectionIndex(int index) { return minSection + index; }
        Chunk at(int y) { sections[(y >> 4) - minSection].at(y & 15); return this; }
    }

    @Test void fullMinecraftStorageAcceptsBothExtremesAndTheReportedBlock() throws Exception {
        var preflight = preflight();
        for (int y : new int[]{-2032, -97, -96, -65, 208, 319, 320, 511, 512, 2031}) {
            assertDoesNotThrow(() -> preflight.check(FULL_STORAGE, new Chunk(-2032, 4064).at(y)), "Y=" + y);
        }
    }

    @Test void anyNonAirCellPastEitherEndpointRejectsTheEntireSource() throws Exception {
        var preflight = preflight();
        for (int y : new int[]{-2033, 2032}) {
            var firstValidChunk = new Chunk(-64, 384).at(208);
            var lastInvalidChunk = new Chunk(-2048, 4096).at(y);
            var failure = assertThrows(IllegalStateException.class,
                () -> preflight.check(FULL_STORAGE, firstValidChunk, lastInvalidChunk));
            assertTrue(failure.getMessage().contains("Y=" + y));
            assertTrue(failure.getMessage().contains("refusing a partial plot copy"));
        }
    }

    @Test void outOfRangeAirDoesNotRejectAnOtherwiseValidShip() throws Exception {
        var preflight = preflight();
        assertDoesNotThrow(() -> preflight.check(FULL_STORAGE, new Chunk(-2048, 4096).at(208)));
        assertDoesNotThrow(() -> preflight.check(LevelHeightAccessor.create(-63, 382),
            new Chunk(-64, 384).at(-63).at(318)), "Partially covered boundary sections may contain only air outside");
        assertThrows(IllegalStateException.class, () -> preflight.check(LevelHeightAccessor.create(-63, 382),
            new Chunk(-64, 384).at(319)), "The exclusive upper bound still rejects a non-air cell");
    }

    @Test void ordinaryNarrowDimensionsRejectInsteadOfClipping() throws Exception {
        var preflight = preflight();
        assertThrows(IllegalStateException.class, () -> preflight.check(LevelHeightAccessor.create(0, 256),
            new Chunk(-64, 384).at(255).at(256)));
    }

    @Test void aChangedParentFrameCannotChangeTheBoundsMidPreflight() throws Exception {
        var routed = new LevelHeightAccessor() {
            int minY = -2032;
            int height = 4064;
            @Override public int getMinBuildHeight() { return minY; }
            @Override public int getHeight() { return height; }
        };
        var source = new Chunk(-2032, 4064).at(-2032).at(2031);
        source.onRead = () -> { routed.minY = -64; routed.height = 384; };
        assertDoesNotThrow(() -> preflight().check(routed, source));
    }

    @Test void inconsistentSourceSectionMetadataCannotHideTailBlocks() throws Exception {
        var source = new Chunk(-2032, 4064).at(2031);
        source.count = 24;
        assertThrows(IllegalStateException.class, () -> preflight().check(FULL_STORAGE, source));
    }

    @Test void productionChecksBeforeAllocationAndBeforeAnyPlacement() throws Exception {
        var transit = read("ipl/sable/transit/SableTransitOps");
        var rehome = read("ipl/sable/transit/SableRehomeOps");
        var execute = method(transit, "executeTransit");
        var move = method(rehome, "rehome");
        for (MethodNode entry : List.of(execute, move)) {
            assertBefore(entry, "preflightPlotCopy", "allocateSubLevel");
            assertTrue(callIndex(entry, "rollbackCopiedPlot") >= 0, "A failed copy must clean up its twin");
        }
        assertCopyFailureReturnsAfterRollback(execute, "copyPlotBlocks");
        assertCopyFailureReturnsAfterRollback(move, "copyPlotBlocksPublic");
        var copy = method(transit, "copyPlotBlocks");
        assertBefore(copy, "forChunk", "requireFits");
        assertBefore(copy, "requireFits", "setIgnoreOnPlace");
        assertBefore(copy, "requireFits", "newEmptyChunk");
        assertBefore(copy, "requireFits", "setBlockState");
        assertEquals(-1, callIndex(copy, "getMinBuildHeight"));
        assertEquals(-1, callIndex(copy, "getMaxBuildHeight"));
        assertBefore(method(transit, "preflightPlotCopy"), "forChunk", "requireFits");
    }

    private record Preflight(Method method) {
        void check(LevelHeightAccessor destination, Chunk... chunks) throws Exception {
            try {
                method.invoke(null, destination, List.of(chunks));
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw failure;
            }
        }
    }

    private static Preflight preflight() throws Exception {
        String fixtureName = HELPER + "$TestFixture";
        var remapping = Map.of(
            HELPER, fixtureName,
            "net/minecraft/world/level/chunk/LevelChunk", Type.getInternalName(Chunk.class),
            "net/minecraft/world/level/chunk/LevelChunkSection", Type.getInternalName(Section.class),
            "net/minecraft/world/level/block/state/BlockState", Type.getInternalName(State.class));
        var writer = new ClassWriter(0);
        try (var input = IplPlotCopyHeightTest.class.getResourceAsStream("/" + HELPER + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassRemapper(writer, new SimpleRemapper(remapping)), 0);
        }
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(IplPlotCopyHeightTest.class.getClassLoader()); }
            Class<?> define() {
                byte[] code = writer.toByteArray();
                return defineClass(fixtureName.replace('/', '.'), code, 0, code.length);
            }
        }
        return new Preflight(new FixtureLoader().define().getMethod("requireFits", LevelHeightAccessor.class, Iterable.class));
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = IplPlotCopyHeightTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input);
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static int callIndex(MethodNode method, String name) {
        int index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static void assertBefore(MethodNode method, String first, String second) {
        int firstIndex = callIndex(method, first);
        int secondIndex = callIndex(method, second);
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex, method.name + ": " + first + " before " + second);
    }

    private static void assertCopyFailureReturnsAfterRollback(MethodNode method, String copy) {
        int copyIndex = callIndex(method, copy);
        var handler = method.tryCatchBlocks.stream().filter(block ->
            "java/lang/Throwable".equals(block.type)
                && method.instructions.indexOf(block.start) <= copyIndex
                && method.instructions.indexOf(block.end) > copyIndex).findFirst().orElseThrow();
        boolean rolledBack = false;
        for (var instruction = handler.handler.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("removeSubLevel", call.name, "The failed copy handler must not remove the source");
                if (call.name.equals("rollbackCopiedPlot")) rolledBack = true;
            }
            if (instruction.getOpcode() == Opcodes.RETURN || instruction.getOpcode() == Opcodes.IRETURN) {
                assertTrue(rolledBack, "The failure path must roll back and return before source removal");
                return;
            }
        }
        fail("The failed copy handler did not return");
    }
}
