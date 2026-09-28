package ipl.sable.transit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PortalTransferBatchTest {
    // A world registry fixture exercises the production transaction without a
    // running Minecraft server. Source and destination may share portal UUIDs.
    private record Face(UUID id, String dimension) {}

    private static final class Worlds {
        final Map<UUID, Face> source = new HashMap<>();
        final Map<UUID, Face> destination = new HashMap<>();
        final Face front = addSource();
        final Face back = addSource();
        final Face bigPortal = addSource();
        final Face farEnd = new Face(UUID.randomUUID(), "nether");
        final List<Face> carried = List.of(front, back);

        Worlds() { destination.put(farEnd.id(), farEnd); }

        private Face addSource() {
            Face face = new Face(UUID.randomUUID(), "overworld");
            source.put(face.id(), face);
            return face;
        }

        Face copy(Face old) { return new Face(old.id(), "nether"); }
        boolean publish(Face copy) { return destination.putIfAbsent(copy.id(), copy) == null; }
        void discard(Face copy) { destination.remove(copy.id(), copy); }
        void retire(Face old) { assertTrue(source.remove(old.id(), old)); }
        void assertUntouched() {
            assertEquals(Map.of(front.id(), front, back.id(), back, bigPortal.id(), bigPortal), source);
            assertEquals(Map.of(farEnd.id(), farEnd), destination);
        }
    }

    @Test void bothFacesKeepTheirIdentityAndOnlyTheCarriedEndpointChangesWorld() {
        Worlds worlds = new Worlds();
        try (var batch = PortalTransferBatch.prepare(worlds.carried, worlds::copy, worlds::publish, worlds::discard)) {
            assertNotNull(batch);
            assertEquals(3, worlds.source.size(), "Sources stay alive until the carrier is ready");
            assertEquals(3, worlds.destination.size());
            batch.commit(worlds::retire);
        }
        assertEquals(Map.of(worlds.bigPortal.id(), worlds.bigPortal), worlds.source);
        assertEquals(3, worlds.destination.size());
        for (Face original : worlds.carried) {
            assertEquals(new Face(original.id(), "nether"), worlds.destination.get(original.id()));
        }
        assertSame(worlds.farEnd, worlds.destination.get(worlds.farEnd.id()));
    }

    @Test void rejectionOfSecondFaceRollsBackFirstAndPreservesOriginalPair() {
        Worlds worlds = new Worlds();
        var batch = PortalTransferBatch.prepare(worlds.carried, worlds::copy,
            copy -> !copy.id().equals(worlds.back.id()) && worlds.publish(copy), worlds::discard);
        assertNull(batch);
        worlds.assertUntouched();
    }

    @Test void copyFailureCannotPublishHalfAPortal() {
        Worlds worlds = new Worlds();
        assertThrows(IllegalStateException.class, () -> PortalTransferBatch.prepare(worlds.carried, old -> {
            if (old == worlds.back) throw new IllegalStateException("Cannot create back face");
            return worlds.copy(old);
        }, worlds::publish, worlds::discard));
        worlds.assertUntouched();
    }

    @Test void joinFailureAfterRegistrationAlsoCleansUpTheThrowingFace() {
        Worlds worlds = new Worlds();
        assertThrows(IllegalStateException.class, () -> PortalTransferBatch.prepare(worlds.carried, worlds::copy, copy -> {
            worlds.publish(copy);
            if (copy.id().equals(worlds.back.id())) throw new IllegalStateException("Join callback failed");
            return true;
        }, worlds::discard));
        worlds.assertUntouched();
    }

    @Test void abandoningPreparedTransitKeepsBothOriginalFaces() {
        Worlds worlds = new Worlds();
        try (var batch = PortalTransferBatch.prepare(worlds.carried, worlds::copy, worlds::publish, worlds::discard)) {
            assertNotNull(batch);
        }
        worlds.assertUntouched();
    }

    @Test void cleanupFailureStillAttemptsEveryFaceExactlyOnce() {
        Worlds worlds = new Worlds();
        List<UUID> discarded = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> PortalTransferBatch.prepare(worlds.carried, worlds::copy,
            copy -> false, copy -> {
                discarded.add(copy.id());
                throw new IllegalStateException("Cleanup callback failed");
            }));
        assertEquals(List.of(worlds.front.id(), worlds.back.id()), discarded);
        worlds.assertUntouched();
    }

    @Test void retirementFailureMustNotDeleteTheOnlySurvivingCopy() {
        Worlds worlds = new Worlds();
        try (var batch = PortalTransferBatch.prepare(worlds.carried, worlds::copy, worlds::publish, worlds::discard)) {
            assertNotNull(batch);
            assertThrows(IllegalStateException.class, () -> batch.commit(old -> {
                worlds.retire(old);
                throw new IllegalStateException("Removal callback failed");
            }));
        }
        assertFalse(worlds.source.containsKey(worlds.front.id()));
        assertEquals(new Face(worlds.front.id(), "nether"), worlds.destination.get(worlds.front.id()));
        assertEquals(3, worlds.destination.size());
    }
}
