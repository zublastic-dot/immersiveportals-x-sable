package qouteall.imm_ptl.core.teleportation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSoundRegistryTest {
    @Test void explicitEntityWorldTransferPreservesIdentityAndAdmissionTime() {
        var registry = new PortalSoundRegistry<Object, Object>(1);
        Object voice = new Object(), first = new Object(), next = new Object();
        assertTrue(registry.bind(voice, first, 17));
        registry.moveEmitter(voice, next);
        assertSame(next, registry.owner(voice).world());
        assertEquals(17, registry.owner(voice).admittedTick());
        registry.moveEmitter(new Object(), first);
        assertEquals(1, registry.size());
    }
    @Test void nativeSoundIdentityAndFirstOwnerSurviveCrossing() {
        var registry = new PortalSoundRegistry<Object, Object>(4);
        Object sound = new Object(), overworld = new Object(), nether = new Object();
        assertTrue(registry.bind(sound, overworld, 0));
        assertTrue(registry.bind(sound, nether, 100));
        assertSame(overworld, registry.owner(sound).world());
        registry.prune(s -> s == sound, world -> true, 100_000);
        assertSame(overworld, registry.owner(sound).world());
    }

    @Test void equalButDistinctInstancesCannotShareAChannelOwner() {
        var registry = new PortalSoundRegistry<Object, Object>(4);
        Object one = new String("same sound"), two = new String("same sound");
        Object worldOne = new Object(), worldTwo = new Object();
        assertEquals(one, two);
        registry.bind(one, worldOne, 0);
        registry.bind(two, worldTwo, 0);
        assertSame(worldOne, registry.owner(one).world());
        assertSame(worldTwo, registry.owner(two).world());
    }

    @Test void queuedSoundsKeepProducerWorldUntilNativeDelayElapses() {
        var registry = new PortalSoundRegistry<Object, Object>(4);
        Object delayed = new Object(), world = new Object();
        registry.bind(delayed, world, 0);
        registry.prune(s -> s == delayed, w -> true, 20_000);
        assertNotNull(registry.owner(delayed));
        registry.prune(s -> false, w -> true, 20_001);
        assertNull(registry.owner(delayed));
    }

    @Test void worldRemovalAndReloadCannotRetainOldOwnership() {
        var registry = new PortalSoundRegistry<Object, Object>(4);
        Object sound = new Object(), oldWorld = new Object(), replacementWorld = new Object();
        registry.bind(sound, oldWorld, 0);
        registry.prune(s -> true, w -> w == replacementWorld, 1);
        assertNull(registry.owner(sound));
        registry.bind(sound, replacementWorld, 2);
        registry.clear();
        assertEquals(0, registry.size());
    }

    @Test void rejectedAndCompletedSoundsExpireAndRegistryRemainsBounded() {
        var registry = new PortalSoundRegistry<Object, Object>(1);
        Object sound = new Object(), world = new Object();
        assertTrue(registry.bind(sound, world, 0));
        assertFalse(registry.bind(new Object(), world, 0));
        registry.prune(s -> false, w -> true, 40);
        assertEquals(1, registry.size());
        registry.prune(s -> false, w -> true, 41);
        assertEquals(0, registry.size());
        assertTrue(registry.bind(new Object(), world, 42));
    }
}
