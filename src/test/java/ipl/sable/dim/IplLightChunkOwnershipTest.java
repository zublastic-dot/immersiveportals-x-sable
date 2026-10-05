package ipl.sable.dim;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LightChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the section-layout failure without starting Minecraft. The section
 * indexing is Minecraft's; the ownership decision is the production helper's.
 * This is not a claim that JVM unit tests apply the runtime mixins.
 */
class IplLightChunkOwnershipTest {
    private record World(String dimension) {}

    private static final class Chunk {
        final World owner;
        final LevelHeightAccessor height;
        final int[] sections;

        Chunk(World owner, int minY, int blockHeight) {
            this.owner = owner;
            height = LevelHeightAccessor.create(minY, blockHeight);
            sections = new int[height.getSectionsCount()];
            for (int i = 0; i < sections.length; i++) {
                // Store the absolute section Y as a visible sentinel for offset corruption.
                sections[i] = height.getSectionYFromSectionIndex(i);
            }
        }
    }

    private static int[] consumeSections(LevelHeightAccessor engineHeight, Chunk chunk) {
        if (chunk == null) return new int[0];
        int[] result = new int[engineHeight.getSectionsCount()];
        for (int i = 0; i < result.length; i++) result[i] = chunk.sections[i];
        return result;
    }

    private static Chunk lightingLookup(World owner, Chunk proxyResult) {
        return IplLightChunkOwnership.select(owner,
            proxyResult == null ? null : proxyResult.owner, proxyResult);
    }

    @Test void rejectsHosted24SectionChunkFrom38SectionParentConsumer() {
        World parent = new World("custom:tall_world"), hosting = new World("custom:hosting");
        var parentHeight = LevelHeightAccessor.create(-96, 608);
        var hosted = new Chunk(hosting, -64, 384);
        assertEquals(38, parentHeight.getSectionsCount());
        assertEquals(24, hosted.sections.length);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> consumeSections(parentHeight, hosted),
            "Negative control: the old visual proxy is not a legal light-engine input");

        int[] original = hosted.sections.clone();
        assertArrayEquals(new int[0], consumeSections(parentHeight, lightingLookup(parent, hosted)));
        assertArrayEquals(original, hosted.sections, "Reject ownership; never resize or rewrite another world's light data");
    }

    @Test void equalSectionCountsWithDifferentMinYStillCorruptTheOldLookup() {
        World parent = new World("custom:lower"), hosting = new World("custom:upper");
        var parentHeight = LevelHeightAccessor.create(-96, 384);
        var hosted = new Chunk(hosting, -64, 384);
        assertEquals(parentHeight.getSectionsCount(), hosted.sections.length);
        int indexAtZero = parentHeight.getSectionIndex(0);
        assertEquals(2, hosted.sections[indexAtZero],
            "Old indexing silently reads section Y=2 for a sample at Y=0");
        assertNotEquals(parentHeight.getSectionYFromSectionIndex(indexAtZero), hosted.sections[indexAtZero]);
        assertNull(lightingLookup(parent, hosted), "An array-length check cannot protect section offsets");
    }

    @Test void identicalDimensionNamesAndHeightDoNotMakeWorldInstancesInterchangeable() {
        World current = new World("custom:world"), stale = new World("custom:world");
        assertEquals(current, stale, "The fixture deliberately has value-equal world keys");
        assertNotSame(current, stale);
        assertNull(lightingLookup(current, new Chunk(stale, -64, 384)),
            "World reloads must not reuse stale owner data even when keys and dimensions match");
    }

    @Test void parentOwnedTerrainAndHostedOwnedTerrainAreBothRetained() {
        World parent = new World("custom:parent"), hosting = new World("custom:hosting");
        Chunk parentChunk = new Chunk(parent, -96, 608), hostedChunk = new Chunk(hosting, -64, 384);
        assertSame(parentChunk, lightingLookup(parent, parentChunk));
        assertSame(hostedChunk, lightingLookup(hosting, hostedChunk));
        assertArrayEquals(parentChunk.sections, consumeSections(parentChunk.height, lightingLookup(parent, parentChunk)));
        assertArrayEquals(hostedChunk.sections, consumeSections(hostedChunk.height, lightingLookup(hosting, hostedChunk)));
    }

    @Test void shorterParentCannotSilentlyModifyOnlyTheFirstPartOfForeignData() {
        World small = new World("custom:small"), tall = new World("custom:tall");
        var smallHeight = LevelHeightAccessor.create(0, 256);
        var tallChunk = new Chunk(tall, -96, 608);
        int[] leaked = consumeSections(smallHeight, tallChunk);
        assertEquals(16, leaked.length);
        assertEquals(-6, leaked[0], "Old route does not crash here but begins in the wrong section");
        assertNull(lightingLookup(small, tallChunk));
    }

    @Test void actualOwningWorldRatherThanCurrentPlayerWorldControlsAcceptance() {
        World first = new World("custom:first"), second = new World("custom:second");
        Chunk firstChunk = new Chunk(first, 0, 256), secondChunk = new Chunk(second, 0, 256);
        // Crossing the portal changes which world is primary, not either chunk's owner.
        for (World playerWorld : Arrays.asList(first, second, first)) {
            assertSame(firstChunk, lightingLookup(first, firstChunk));
            assertSame(secondChunk, lightingLookup(second, secondChunk));
            assertNull(lightingLookup(playerWorld, playerWorld == first ? secondChunk : firstChunk));
        }
    }

    @Test void missingChunkDoesNotBecomeAnEmptyOrForeignReplacement() {
        assertNull(lightingLookup(new World("custom:unloaded"), null));
    }

    @Test void ownershipFilteringDoesNotMutateTheRenderProxy() {
        World parent = new World("custom:parent"), hosting = new World("custom:hosting");
        Chunk visualProxy = new Chunk(hosting, -64, 384);
        int[] original = visualProxy.sections.clone();
        assertNull(lightingLookup(parent, visualProxy));
        assertSame(hosting, visualProxy.owner);
        assertArrayEquals(original, visualProxy.sections);
        assertSame(visualProxy, lightingLookup(hosting, visualProxy));
    }

    @Test void nonLevelChunkProvidersStayOnTheirExistingPathWithoutProbingOrLoadingThem() {
        LightChunk provider = (LightChunk) Proxy.newProxyInstance(LightChunk.class.getClassLoader(),
            new Class<?>[]{LightChunk.class}, (proxy, method, args) -> {
                throw new AssertionError("Ownership must not inspect or load non-LevelChunk data: " + method);
            });
        assertTrue(IplLightChunkOwnership.belongsTo(null, 15, -8, provider));
        assertSame(provider, IplLightChunkOwnership.forWorld(null, 15, -8, provider));
    }

    @Test void productionHelperPreservesMissingLookupResults() {
        assertTrue(IplLightChunkOwnership.belongsTo(null, 15, -8, null));
        assertNull(IplLightChunkOwnership.forWorld(null, 15, -8, null));
    }
}
