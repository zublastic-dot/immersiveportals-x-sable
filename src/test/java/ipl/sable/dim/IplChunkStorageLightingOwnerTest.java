package ipl.sable.dim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for ScalableLux's BlockAndTintGetter-based light-backend ownership branch. */
class IplChunkStorageLightingOwnerTest {
    private static final class Owner {
        int minY = 0, height = 256, heightReads;
        LevelLightEngine engine;
        final List<String> reads = new ArrayList<>();
        final IllegalStateException blockRead = new IllegalStateException("owner block lookup");
        final BlockAndTintGetter getter = (BlockAndTintGetter) Proxy.newProxyInstance(
            BlockAndTintGetter.class.getClassLoader(), new Class<?>[]{BlockAndTintGetter.class},
            (proxy, method, args) -> {
                reads.add(method.getName());
                return switch (method.getName()) {
                    case "getMinBuildHeight" -> { heightReads++; yield minY; }
                    case "getHeight" -> { heightReads++; yield height; }
                    case "getLightEngine" -> engine;
                    case "getShade" -> 0.75f;
                    case "getBlockTint" -> 0x123456;
                    case "getBlockEntity", "getFluidState" -> null;
                    case "getBlockState" -> throw blockRead;
                    default -> throw new AssertionError("Unexpected owner access: " + method.getName());
                };
            });
    }

    /** A distinct backend object, created without chunks or a Minecraft world. */
    private static final class OwnerLightEngine extends LevelLightEngine {
        OwnerLightEngine(BlockGetter owner) {
            super(new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) {
                    throw new AssertionError("Storage pinning must not load lighting chunks");
                }
                @Override public BlockGetter getLevel() { return owner; }
            }, false, false);
        }
    }

    @Test void pinnedHeightPreservesTheOwnerBackendCapabilityLostByAPlainAccessor() {
        Owner owner = new Owner();
        owner.engine = new OwnerLightEngine(owner.getter);
        LevelHeightAccessor plain = LevelHeightAccessor.create(-2032, 4064);
        assertFalse(plain instanceof BlockAndTintGetter,
            "The old replacement cannot enter ScalableLux's backend-identification branch");

        var storage = IplChunkStorageHeight.select(owner.getter, true, -2032, 4064, 254);
        var getter = assertInstanceOf(BlockAndTintGetter.class, storage);
        assertSame(owner.engine, getter.getLightEngine(),
            "ScalableLux's instanceof test must see the original owner's exact engine");
        assertEquals(-2032, storage.getMinBuildHeight());
        assertEquals(2032, storage.getMaxBuildHeight());
        assertEquals(254, storage.getSectionsCount());
        assertEquals(0, owner.heightReads);
    }

    @Test void parentFrameChangesCannotLeakIntoAnyDerivedStorageCoordinate() {
        Owner owner = new Owner();
        var storage = IplChunkStorageHeight.select(owner.getter, true, -2032, 4064, -1);
        for (int[] frame : new int[][]{{0, 256}, {-96, 608}, {-2032, 16}, {2016, 16}}) {
            owner.minY = frame[0];
            owner.height = frame[1];
            assertEquals(4064, storage.getHeight());
            assertEquals(-127, storage.getMinSection());
            assertEquals(127, storage.getMaxSection());
            assertEquals(254, storage.getSectionsCount());
            assertEquals(0, storage.getSectionIndex(-2032));
            assertEquals(253, storage.getSectionIndex(2031));
            assertFalse(storage.isOutsideBuildHeight(-2032));
            assertFalse(storage.isOutsideBuildHeight(2031));
            assertTrue(storage.isOutsideBuildHeight(-2033));
            assertTrue(storage.isOutsideBuildHeight(2032));
        }
        assertEquals(0, owner.heightReads, "Every height-derived default belongs to the immutable wrapper");
        assertTrue(owner.reads.isEmpty(), "Pinning and section indexing must not query world services");
    }

    @Test void ownerServicesForwardWithoutCapturingAnUninitializedEngine() {
        Owner owner = new Owner();
        var getter = assertInstanceOf(BlockAndTintGetter.class,
            IplChunkStorageHeight.select(owner.getter, true, -2032, 4064, -1));
        assertNull(getter.getLightEngine());
        owner.engine = new OwnerLightEngine(owner.getter);
        assertSame(owner.engine, getter.getLightEngine(), "Backend lookup is forwarded when used");
        assertEquals(0.75f, getter.getShade(Direction.UP, true));
        assertEquals(0x123456, getter.getBlockTint(BlockPos.ZERO, (biome, x, z) -> 0));
        assertNull(getter.getBlockEntity(BlockPos.ZERO));
        assertNull(getter.getFluidState(BlockPos.ZERO));
        assertSame(owner.blockRead, assertThrows(IllegalStateException.class,
            () -> getter.getBlockState(BlockPos.ZERO)));
        assertEquals(List.of("getLightEngine", "getLightEngine", "getShade", "getBlockTint",
            "getBlockEntity", "getFluidState", "getBlockState"), owner.reads);
        assertEquals(0, owner.heightReads);
    }

    @Test void ordinaryOwnersRemainUnwrappedAndMalformedArraysStillFailBeforeSideEffects() {
        Owner owner = new Owner();
        assertSame(owner.getter, IplChunkStorageHeight.select(owner.getter, false, -2032, 4064, 24));
        assertThrows(IllegalStateException.class,
            () -> IplChunkStorageHeight.select(owner.getter, true, -2032, 4064, 24));
        assertTrue(owner.reads.isEmpty());
    }
}
