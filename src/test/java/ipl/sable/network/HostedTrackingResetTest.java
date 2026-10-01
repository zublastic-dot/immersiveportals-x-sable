package ipl.sable.network;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostedTrackingResetTest {
    @Test void dimensionCleanupWaitsForGameplayAndSendsOnce() {
        var state = new HostedTrackingReset.Client<Object>();
        Object connection = new Object();
        assertEquals(0, state.takeRequest(connection, true));
        state.onCleanup(connection);
        assertEquals(0, state.takeRequest(connection, false));
        assertEquals(0, state.takeRequest(connection, false));
        assertEquals(1, state.takeRequest(connection, true));
        for (int tick = 0; tick < 200; tick++) {
            assertEquals(0, state.takeRequest(connection, true), "no periodic resync");
        }
    }

    @Test void multipleCleanupsBeforeNextGameTickCoalesce() {
        var state = new HostedTrackingReset.Client<Object>();
        Object connection = new Object();
        state.takeRequest(connection, true);
        state.onCleanup(connection);
        state.onCleanup(connection);
        assertEquals(2, state.takeRequest(connection, true));
        assertEquals(0, state.takeRequest(connection, true));
    }

    @Test void exitCancelsPendingRequestEvenIfConnectionObjectStillExists() {
        var state = new HostedTrackingReset.Client<Object>();
        Object connection = new Object();
        state.takeRequest(connection, true);
        state.onCleanup(connection);
        state.clear();
        assertEquals(0, state.takeRequest(connection, true));
        assertEquals(0, state.takeRequest(connection, true));
    }

    @Test void connectionReplacementCannotReplayPreviousWorldReset() {
        var state = new HostedTrackingReset.Client<Object>();
        // Equal values deliberately prove that connection identity, not equality, matters.
        Object oldConnection = new String("server"), newConnection = new String("server");
        state.takeRequest(oldConnection, true);
        state.onCleanup(oldConnection);
        assertEquals(0, state.takeRequest(newConnection, true));
        assertEquals(0, state.takeRequest(newConnection, true));
        state.onCleanup(newConnection);
        assertEquals(2, state.takeRequest(newConnection, true), "new connection can request its own reset");
    }

    @Test void disconnectWithoutExitEventAlsoCancelsPendingRequest() {
        var state = new HostedTrackingReset.Client<Object>();
        Object connection = new Object();
        state.takeRequest(connection, true);
        state.onCleanup(connection);
        assertEquals(0, state.takeRequest(null, false));
        assertEquals(0, state.takeRequest(connection, true));
    }

    @Test void cleanupDuringInitialLoginOrConnectionReplacementIsNotAReset() {
        var state = new HostedTrackingReset.Client<Object>();
        Object first = new Object(), second = new Object();
        state.onCleanup(first);
        assertEquals(0, state.takeRequest(first, true));
        state.onCleanup(second);
        assertEquals(0, state.takeRequest(second, true));
        state.onCleanup(null);
        assertEquals(0, state.takeRequest(second, true));
    }

    @Test void rapidSecondCleanupSurvivesServerCooldown() {
        var client = new HostedTrackingReset.Client<Object>();
        var server = new HostedTrackingReset.Server(20);
        Object connection = new Object();
        client.takeRequest(connection, true);
        client.onCleanup(connection);
        assertTrue(server.request(client.takeRequest(connection, true)));
        assertEquals(1, server.takeRequest(100));

        client.onCleanup(connection);
        assertTrue(server.request(client.takeRequest(connection, true)));
        assertEquals(0, server.takeRequest(101));
        assertEquals(0, server.takeRequest(119));
        assertEquals(2, server.takeRequest(120), "new world still needs allocation after cooldown");
        assertEquals(0, server.takeRequest(1000));
    }

    @Test void delayedAndDuplicateRequestsCannotLoseLatestCleanup() {
        var server = new HostedTrackingReset.Server(20);
        assertTrue(server.request(1));
        assertEquals(1, server.takeRequest(10));
        assertTrue(server.request(2));
        assertTrue(server.request(3));
        assertFalse(server.request(2));
        assertFalse(server.request(3));
        assertEquals(0, server.takeRequest(29));
        assertEquals(3, server.takeRequest(30));
        assertFalse(server.request(1));
        assertFalse(server.request(3));
        assertEquals(0, server.takeRequest(100));
    }

    @Test void invalidRevisionDoesNotScheduleWork() {
        var server = new HostedTrackingReset.Server(20);
        assertFalse(server.request(0));
        assertFalse(server.request(-1));
        assertEquals(0, server.takeRequest(0));
    }

    @Test void newServerConnectionHasIndependentRevisionAndCooldown() {
        var oldConnection = new HostedTrackingReset.Server(20);
        var newConnection = new HostedTrackingReset.Server(20);
        oldConnection.request(100);
        assertEquals(100, oldConnection.takeRequest(1000));
        assertTrue(newConnection.request(1));
        assertEquals(1, newConnection.takeRequest(1001));
    }

    @Test void resetReplayStateIsIsolatedBetweenConnections() {
        var sender = new HostedTrackingReset.Replay<String>();
        var other = new HostedTrackingReset.Replay<String>();
        sender.reset(List.of("ship"));
        assertTrue(sender.needsReplay("ship"));
        assertFalse(other.needsReplay("ship"));
        sender.synced("ship");
        assertEquals(0, sender.size());
        assertEquals(0, other.size());
    }

    @Test void inFlightAllocationBecomingIneligibleStillReceivesNormalRemoval() {
        UUID sender = UUID.randomUUID(), other = UUID.randomUUID();
        var tracking = new HashSet<>(List.of(sender, other));
        var clientShips = new HashSet<>(List.of("ship")); // Full sync already in flight at cleanup.
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("ship"));
        assertTrue(tracking.contains(sender), "reset must preserve the server's removal obligation");

        // Existing normal removal runs when the viewer becomes ineligible. Erasing
        // tracking at reset would skip this packet and leave the fresh client ghost.
        if (tracking.remove(sender)) clientShips.remove("ship");
        replay.retainTracked(ship -> tracking.contains(sender));
        assertTrue(clientShips.isEmpty());
        assertTrue(tracking.contains(other));
        assertEquals(0, replay.size());
    }

    @Test void temporaryIneligibilityDoesNotConsumeAnAlreadyTrackedShipsReplay() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("ship"));
        for (int tick = 0; tick < 100; tick++) {
            // A delayed portal-view watcher can leave server tracking retained,
            // even when the ship is not in this tick's eligible bootstrap set.
            replay.retainTracked(ship -> true);
        }
        assertTrue(replay.needsReplay("ship"));
        replay.synced("ship"); // Eligibility arrives; full sync succeeds.
        assertFalse(replay.needsReplay("ship"));
    }

    @Test void eachEligibleTrackedShipReplaysOnceWithoutRecurringSync() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("first", "second"));
        int syncs = 0;
        for (int tick = 0; tick < 100; tick++) {
            for (String ship : List.of("first", "second")) {
                if (replay.needsReplay(ship)) {
                    syncs++;
                    replay.synced(ship);
                }
            }
        }
        assertEquals(2, syncs);
        assertEquals(0, replay.size());
    }

    @Test void removedAndNoLongerTrackedShipsArePruned() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("removed", "untracked", "retained"));
        replay.retainTracked("retained"::equals);
        assertEquals(1, replay.size());
        assertTrue(replay.needsReplay("retained"));
    }

    @Test void laterCleanupRequiresReplayEvenForAnAlreadyRepairedShip() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("ship"));
        replay.synced("ship");
        replay.reset(List.of("ship"));
        assertTrue(replay.needsReplay("ship"));
    }

    @Test void replaySetIsBoundedToLatestTrackedSnapshot() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("old", "retained"));
        var current = new HashSet<>(List.of("retained", "new"));
        replay.reset(current);
        current.clear(); // Snapshot is owned by the replay state.
        assertEquals(2, replay.size());
        assertFalse(replay.needsReplay("old"));
        assertTrue(replay.needsReplay("retained"));
        assertTrue(replay.needsReplay("new"));
        assertFalse(replay.needsReplay("never-tracked"));
    }

    @Test void failedSendDoesNotConsumeReplay() {
        var replay = new HostedTrackingReset.Replay<String>();
        replay.reset(List.of("ship"));
        assertTrue(replay.needsReplay("ship"));
        // No synced call until the full sync and parent/session packets succeed.
        replay.retainTracked(ship -> true);
        assertTrue(replay.needsReplay("ship"));
        replay.synced("ship");
        assertEquals(0, replay.size());
    }
}
