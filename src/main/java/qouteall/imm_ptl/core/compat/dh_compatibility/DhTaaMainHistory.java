package qouteall.imm_ptl.core.compat.dh_compatibility;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Metadata only: DH owns the image until a crossing copies it into a bounded private view. */
public record DhTaaMainHistory(Object dimension, int framebuffer, int width, int height,
                               boolean zeroToOne, DhTaaHistory.Snapshot snapshot) {
    public DhTaaCrossing.Key returnKey(Object origin, Object destination, UUID reversePortal, int frame, long now) {
        if (reversePortal == null || !Objects.equals(dimension, origin) || snapshot == null || snapshot.phase() < 0
            || frame - snapshot.frame() < 0 || frame - snapshot.frame() > 1
            || now < snapshot.time() || now - snapshot.time() >= 500_000_000L) return null;
        return new DhTaaCrossing.Key(destination, origin, List.of(reversePortal));
    }
}
