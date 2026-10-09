package ipl.sable.dim;

import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;
import net.minecraft.world.level.dimension.DimensionType;

import static org.junit.jupiter.api.Assertions.*;

/** Storage regressions plus bytecode wiring contracts; these do not simulate a live Mixin launch. */
class IplChunkStorageHeightTest {
    private static final String HELPER = "ipl/sable/dim/IplChunkStorageHeight";
    private static final String CONSTRUCTOR = "(Lnet/minecraft/world/level/ChunkPos;"
        + "Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/level/LevelHeightAccessor;"
        + "Lnet/minecraft/core/Registry;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
        + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
    private static final String NEW_CHUNK = "(Lnet/minecraft/world/level/ChunkPos;)V";

    /** Models the hosting Level's scalar routes, including its unchanged getHeight(). */
    private static final class RoutedHeight implements LevelHeightAccessor {
        int minY = -64;
        int maxY = 320;

        @Override public int getMinBuildHeight() { return minY; }
        @Override public int getHeight() { return 384; }
        @Override public int getMaxBuildHeight() { return maxY; }

        void frame(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    @Test void parentFrameCannotMoveAnExistingStoredBlockToAnotherSection() {
        var routed = new RoutedHeight();
        var storage = IplChunkStorageHeight.select(routed, true, -64, 384, 24);
        String[] sections = new String[storage.getSectionsCount()];
        sections[storage.getSectionIndex(208)] = "obsidian";
        assertEquals(17, storage.getSectionIndex(208));

        routed.frame(-96, 512);
        assertEquals(19, routed.getSectionIndex(208), "The old live accessor selects a different section");
        assertNull(sections[routed.getSectionIndex(208)], "The old path reads an empty section");
        assertEquals("obsidian", sections[storage.getSectionIndex(208)]);
        assertEquals(-64, storage.getMinBuildHeight());
        assertEquals(320, storage.getMaxBuildHeight());
        assertEquals(24, storage.getSectionsCount());

        routed.frame(0, 256);
        assertEquals("obsidian", sections[storage.getSectionIndex(208)], "Another parent must not move storage either");
    }

    @Test void creationInsideAnArmedFrameStillAllocatesTheHostingProfile() {
        var routed = new RoutedHeight();
        routed.frame(-96, 512);
        assertEquals(38, routed.getSectionsCount());
        var plotAllocation = IplChunkStorageHeight.select(routed, true, -64, 384, -1);
        int suppliedCount = plotAllocation.getSectionsCount();
        var chunkStorage = IplChunkStorageHeight.select(routed, true, -64, 384, suppliedCount);
        assertEquals(24, suppliedCount);
        assertEquals(17, chunkStorage.getSectionIndex(208));
        routed.frame(-64, 320);
        assertEquals(24, chunkStorage.getSectionsCount());
        assertEquals(17, chunkStorage.getSectionIndex(208));
    }

    @Test void ordinaryAndCustomAccessorsKeepTheirIdentityAndBehavior() {
        var custom = new RoutedHeight();
        custom.frame(-160, 864);
        assertSame(custom, IplChunkStorageHeight.select(custom, false, -64, 384, 123));
        assertSame(custom, IplChunkStorageHeight.forChunk(custom, 123), "Non-Level accessors are untouched");
        custom.frame(-256, 1024);
        assertEquals(-256, custom.getMinBuildHeight());
        assertEquals(80, custom.getSectionsCount());
    }

    @Test void hostingStorageUsesItsDimensionProfileWithoutHardcodedBounds() {
        var routed = new RoutedHeight();
        var storage = IplChunkStorageHeight.select(routed, true, -128, 512, 32);
        assertEquals(-128, storage.getMinBuildHeight());
        assertEquals(384, storage.getMaxBuildHeight());
        assertEquals(32, storage.getSectionsCount());
        assertEquals(21, storage.getSectionIndex(208));
    }

    @Test void malformedSuppliedSectionsAreRejectedInsteadOfDiscarded() {
        var routed = new RoutedHeight();
        var failure = assertThrows(IllegalStateException.class,
            () -> IplChunkStorageHeight.select(routed, true, -64, 384, 38));
        assertTrue(failure.getMessage().contains("refusing to discard supplied sections"));
        assertDoesNotThrow(() -> IplChunkStorageHeight.select(routed, true, -64, 384, 24));
        assertDoesNotThrow(() -> IplChunkStorageHeight.select(routed, true, -64, 384, -1));
    }

    @Test void bundledStorageCoversTheEntireMinecraft1211DimensionEnvelope() throws Exception {
        qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/data/ipl_sable/dimension_type/sublevels.json")) {
            assertNotNull(input);
            var json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            int minY = json.get("min_y").getAsInt();
            int height = json.get("height").getAsInt();
            assertEquals(DimensionType.MIN_Y, minY);
            assertEquals(DimensionType.MAX_Y + 1, minY + height);
            var storage = IplChunkStorageHeight.select(new RoutedHeight(), true, minY, height, -1);
            assertEquals(254, storage.getSectionsCount());
            assertEquals(0, storage.getSectionIndex(-2032));
            assertEquals(253, storage.getSectionIndex(2031));
            assertEquals(140, storage.getSectionIndex(208));
            // The light engine needs a section of padding on both sides; saved light
            // section Y still fits vanilla's signed-byte representation at both edges.
            assertEquals(-128, storage.getMinSection() - 1);
            assertEquals(127, storage.getMaxSection());
        }
    }

    @Test void everyLegalAlignedParentRangeLeavesStoredCoordinatesUnchanged() {
        var routed = new RoutedHeight();
        var storage = IplChunkStorageHeight.select(routed, true, -2032, 4064, 254);
        int profiles = 0;
        for (int min = -2032; min <= 2016; min += 16) {
            for (int max = min + 16; max <= 2032; max += 16) {
                routed.frame(min, max);
                for (int y : new int[] {min, max - 1, -65, -64, 319, 320, 208}) {
                    int index = storage.getSectionIndex(y);
                    assertTrue(index >= 0 && index < 254);
                    assertEquals(y >> 4, storage.getSectionYFromSectionIndex(index));
                }
                assertEquals(-2032, storage.getMinBuildHeight());
                assertEquals(2032, storage.getMaxBuildHeight());
                profiles++;
            }
        }
        assertEquals(32385, profiles);
    }

    @Test void chunkAccessorIsReplacedAtConstructorHeadBeforeStorageAllocation() throws Exception {
        var vanilla = read("net/minecraft/world/level/chunk/ChunkAccess");
        assertTrue(vanilla.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(CONSTRUCTOR)));
        var mixin = read("ipl/sable/mixin/IplHostingChunkHeightMixin");
        var hook = annotated(mixin, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        var annotation = annotation(hook, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(List.of("<init>" + CONSTRUCTOR), value(annotation, "method"));
        assertEquals(1, value(annotation, "require"));
        assertEquals(3, value(annotation, "index"));
        assertEquals(Boolean.TRUE, value(annotation, "argsOnly"));
        assertEquals("HEAD", value(at(annotation), "value"));
        assertTrue((hook.access & Opcodes.ACC_STATIC) != 0, "The hook must not access an uninitialized this");
        assertEquals("(Lnet/minecraft/world/level/LevelHeightAccessor;" + CONSTRUCTOR.substring(1, CONSTRUCTOR.length() - 1)
            + "Lnet/minecraft/world/level/LevelHeightAccessor;", hook.desc, "Capture all constructor arguments, including supplied sections");
        assertTrue(calls(hook, HELPER, "forChunk"));
    }

    @Test void sablePreallocationUsesTheSameStorageProfileAndRequiresItsExactCall() throws Exception {
        var target = read("dev/ryanhcode/sable/sublevel/plot/LevelPlot").methods.stream()
            .filter(m -> m.name.equals("newEmptyChunk") && m.desc.equals(NEW_CHUNK)).findFirst().orElseThrow();
        assertEquals(1, callsNamed(target, "net/minecraft/world/level/Level", "getSectionsCount"));
        var hook = annotated(read("ipl/sable/mixin/IplHostingPlotHeightMixin"),
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        var annotation = annotation(hook, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("newEmptyChunk" + NEW_CHUNK), value(annotation, "method"));
        assertEquals(1, value(annotation, "require"));
        assertEquals("INVOKE", value(at(annotation), "value"));
        assertEquals("Lnet/minecraft/world/level/Level;getSectionsCount()I", value(at(annotation), "target"));
        assertTrue(calls(hook, HELPER, "forChunk"));
        assertTrue(calls(hook, "ipl/sable/dim/IplDimAgnostic", "isHostingLevel"));
        assertTrue(calls(hook, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"),
            "Other levels preserve the original operation and any wrappers");
    }

    @Test void productionReadsDimensionTypeAndRegistersBothGuards() throws Exception {
        var method = read(HELPER).methods.stream().filter(m -> m.name.equals("forChunk")).findFirst().orElseThrow();
        assertTrue(calls(method, "ipl/sable/dim/IplDimAgnostic", "isHostingLevel"));
        assertTrue(calls(method, "net/minecraft/world/level/Level", "dimensionType"));
        assertTrue(calls(method, "net/minecraft/world/level/dimension/DimensionType", "minY"));
        assertTrue(calls(method, "net/minecraft/world/level/dimension/DimensionType", "height"));
        assertFalse(calls(method, "net/minecraft/world/level/Level", "getMinBuildHeight"));
        assertFalse(calls(method, "net/minecraft/world/level/Level", "getSectionsCount"));
        try (var input = getClass().getResourceAsStream("/ipl_sable.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("\"IplHostingChunkHeightMixin\""));
            assertTrue(config.contains("\"IplHostingPlotHeightMixin\""));
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = IplChunkStorageHeightTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        var result = new ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static AnnotationNode annotation(MethodNode method, String type) {
        return annotations(method).stream().filter(a -> a.desc.equals(type)).findFirst().orElseThrow();
    }

    private static MethodNode annotated(ClassNode node, String type) {
        return node.methods.stream().filter(m -> annotations(m).stream().anyMatch(a -> a.desc.equals(type)))
            .findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode at(AnnotationNode annotation) {
        Object at = value(annotation, "at");
        return at instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) at;
    }

    private static long callsNamed(MethodNode method, String owner, String name) {
        return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
            .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)).count();
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        return callsNamed(method, owner, name) > 0;
    }
}
