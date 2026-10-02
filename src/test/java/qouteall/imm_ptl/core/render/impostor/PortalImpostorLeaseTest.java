package qouteall.imm_ptl.core.render.impostor;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.render.impostor.PortalImpostorMetadataTest.*;

class PortalImpostorLeaseTest {
    @Test void samePrimaryPortalIdsDoNotPermitReattachingEitherEndpoint() {
        var m = fixture(PORTAL, REVERSE);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000,
            "source-ship:overworld:local-pose", "destination-ship:nether:local-pose");
        assertTrue(lease.attachmentsMatch("source-ship:overworld:local-pose", "destination-ship:nether:local-pose"));
        assertFalse(lease.attachmentsMatch("replacement-ship:overworld:local-pose", "destination-ship:nether:local-pose"));
        assertFalse(lease.attachmentsMatch("source-ship:overworld:local-pose", "replacement-ship:nether:local-pose"));
        assertFalse(lease.attachmentsMatch("source-ship:overworld:moved-local-pose", "destination-ship:nether:local-pose"));
        assertFalse(lease.attachmentsMatch("source-ship:overworld:local-pose", "destination-ship:nether:rotated-local-pose"));
        assertEquals(PORTAL, lease.metadata.sourceAnchor());
        assertEquals(REVERSE, lease.metadata.destinationAnchor());
    }

    @Test void attachmentChecksAlsoApplyWhileDormantAndAfterRenewal() {
        var m = fixture(PORTAL, REVERSE);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000, "source", "destination");
        assertTrue(lease.observeChunkUnload(m, 1100));
        assertTrue(lease.renew(2000));
        assertTrue(lease.attachmentsMatch("source", "destination"));
        assertFalse(lease.attachmentsMatch("source", "destination-in-a-new-dimension"));
        assertFalse(lease.attachmentsMatch(null, "destination"));
        assertFalse(lease.attachmentsMatch("source", null));
    }

    @Test void rigidWorldMotionKeepsCapturedAttachmentsWhileUnknownAnchorsFailClosed() {
        var m = fixture(PORTAL, REVERSE);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000, "source", "destination");
        var moved = at(m, m.origin().add(100, 10, 0), W, H, m.destination().add(20, 0, 0), m.destinationAxisW(), H);
        assertTrue(lease.observeLoaded(moved, 1100));
        assertTrue(lease.attachmentsMatch("source", "destination"));
        var unknown = new PortalImpostorLease(new UUID(1, 2), 6, m, 1000);
        assertFalse(unknown.attachmentsMatch(null, null));
    }

    @Test void unanchoredEndpointsNeedNoAttachmentButCannotGainOneSilently() {
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, fixture(null, null), 1000);
        assertTrue(lease.attachmentsMatch(null, null));
        assertFalse(lease.attachmentsMatch("new-source", null));
        assertFalse(lease.attachmentsMatch(null, "new-destination"));
    }

    @Test void anUnexplainedMissingEntityNeverBecomesDormant() {
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, fixture(null, null), 1000);
        assertTrue(lease.isAlive(1001));
        assertFalse(lease.dormant());
        assertFalse(lease.missingIsExpected(1001));
        assertTrue(lease.renew(2000));
        assertFalse(lease.missingIsExpected(2001));
    }

    @Test void witnessedSourceChunkUnloadPreservesOnlyTheKnownLink() {
        var m = fixture(null, null);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000);
        assertTrue(lease.observeChunkUnload(m, 1100));
        assertTrue(lease.dormant());
        assertTrue(lease.missingIsExpected(1101));
        assertEquals(m, lease.metadata);
        assertEquals(5, lease.generation);
    }

    @Test void aChangedPortalCannotObtainDormancyByUnloading() {
        var m = fixture(null, null);
        var changed = at(m, m.origin(), W, H, m.destination().add(1, 0, 0), m.destinationAxisW(), H);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000);
        assertFalse(lease.observeChunkUnload(changed, 1100));
        assertFalse(lease.missingIsExpected(1101));
        assertEquals(m, lease.metadata);
    }

    @Test void reloadMustRevalidateAndConsumesTheOldUnloadProof() {
        var m = fixture(null, null);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000);
        assertTrue(lease.observeChunkUnload(m, 1100));
        assertTrue(lease.observeLoaded(m, 1200));
        assertFalse(lease.dormant());
        assertFalse(lease.missingIsExpected(1201));
        var changed = at(m, m.origin().add(10, 0, 0), W, H, m.destination(), m.destinationAxisW(), H);
        assertFalse(lease.observeLoaded(changed, 1202));
    }

    @Test void leaseExpiresAtTheBoundaryAndCannotBeResurrectedByLateRenewalOrLoad() {
        var m = fixture(null, null);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000);
        assertTrue(lease.observeChunkUnload(m, 1100));
        assertTrue(lease.missingIsExpected(10_999));
        assertFalse(lease.isAlive(11_000));
        assertFalse(lease.missingIsExpected(11_000));
        assertFalse(lease.renew(11_000));
        assertFalse(lease.observeLoaded(m, 11_001));
        assertFalse(lease.observeChunkUnload(m, 11_001));
    }

    @Test void renewedDormantLeaseKeepsItsGenerationAndExtendsOnlyTheLease() {
        var m = fixture(null, null);
        var token = new UUID(1, 1);
        var lease = new PortalImpostorLease(token, 5, m, 1000);
        assertTrue(lease.observeChunkUnload(m, 1100));
        assertTrue(lease.renew(9000));
        assertTrue(lease.missingIsExpected(18_999));
        assertFalse(lease.missingIsExpected(19_000));
        assertEquals(token, lease.token);
        assertEquals(5, lease.generation);
        assertSame(m, lease.metadata);
    }

    @Test void rigidCarrierPoseCanRefreshButRelinkingAfterDormancyCannot() {
        var m = fixture(PORTAL, REVERSE);
        var lease = new PortalImpostorLease(new UUID(1, 1), 5, m, 1000);
        assertTrue(lease.observeChunkUnload(m, 1100));
        var moved = at(m, m.origin().add(100, 10, 0), W, H, m.destination(), m.destinationAxisW(), H);
        assertTrue(lease.observeLoaded(moved, 1200));
        assertEquals(moved, lease.metadata);
        assertFalse(lease.dormant());
        assertTrue(lease.observeChunkUnload(moved, 1300));
        var detached = fixture(null, REVERSE);
        assertFalse(lease.observeLoaded(detached, 1400));
        assertEquals(moved, lease.metadata);
    }
}
