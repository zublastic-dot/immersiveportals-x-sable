package qouteall.imm_ptl.core.render.impostor;

import java.util.Objects;
import java.util.UUID;

/** Server-thread state; a missing entity is only legitimate after a witnessed chunk unload. */
final class PortalImpostorLease {
    final UUID token;
    final long generation;
    PortalImpostorMetadata metadata;
    private final String sourceAttachment;
    private final String destinationAttachment;
    private long expiresAt;
    private boolean dormant;

    PortalImpostorLease(UUID token, long generation, PortalImpostorMetadata metadata, long now) {
        this(token, generation, metadata, now, null, null);
    }

    PortalImpostorLease(UUID token, long generation, PortalImpostorMetadata metadata, long now,
                       String sourceAttachment, String destinationAttachment) {
        this.token = token;
        this.generation = generation;
        this.metadata = metadata;
        this.sourceAttachment = sourceAttachment;
        this.destinationAttachment = destinationAttachment;
        expiresAt = now + PortalImpostorSync.SERVER_LEASE_MILLIS;
    }

    boolean isAlive(long now) { return now < expiresAt; }
    boolean dormant() { return dormant; }
    boolean missingIsExpected(long now) { return dormant && isAlive(now); }

    /** Reusing an anchor's portal UUID cannot authorize a new ship or local attachment. */
    boolean attachmentsMatch(String currentSource, String currentDestination) {
        return (metadata.sourceAnchor() == null || sourceAttachment != null)
            && (metadata.destinationAnchor() == null || destinationAttachment != null)
            && Objects.equals(sourceAttachment, currentSource)
            && Objects.equals(destinationAttachment, currentDestination);
    }

    boolean renew(long now) {
        if (!isAlive(now)) return false;
        expiresAt = now + PortalImpostorSync.SERVER_LEASE_MILLIS;
        return true;
    }

    boolean observeLoaded(PortalImpostorMetadata current, long now) {
        if (!isAlive(now) || !metadata.linkMatches(current)) return false;
        metadata = current;
        dormant = false;
        return true;
    }

    boolean observeChunkUnload(PortalImpostorMetadata last, long now) {
        if (!observeLoaded(last, now)) return false;
        dormant = true;
        return true;
    }
}
