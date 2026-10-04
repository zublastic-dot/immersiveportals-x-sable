package qouteall.imm_ptl.core.lighting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Numeric-only, finite evidence for exact aperture-key churn. Never participates in cache decisions. */
final class PortalLightCacheTrace {
    private static final String[] COMPONENTS = {
        "center.x", "center.y", "center.z", "inward.x", "inward.y", "inward.z",
        "u.x", "u.y", "u.z", "v.x", "v.y", "v.z", "width", "height",
        "sourceCenter.x", "sourceCenter.y", "sourceCenter.z", "sourceNormal.x", "sourceNormal.y", "sourceNormal.z",
        "sourceU.x", "sourceU.y", "sourceU.z", "sourceV.x", "sourceV.y", "sourceV.z"
    };
    record Face(UUID portal, String receiver, int inwardX, int inwardY, int inwardZ) {}
    record Sample(String receiverIdentity, String sourceIdentity, double[] components) {
        Sample {
            if (components.length != COMPONENTS.length) throw new IllegalArgumentException("Aperture key requires 26 components");
            components = components.clone();
        }
    }
    private static final class Entry {
        Sample previous, changedFrom, changedTo;
        long observations, changes, misses, lastChange;
        double maxPositionDelta, maxBasisDelta;
    }
    private final int limit;
    private final Map<Face, Entry> entries = new LinkedHashMap<>();
    private long capture = Long.MIN_VALUE;

    PortalLightCacheTrace(int limit) {
        if (limit < 1 || limit > 4) throw new IllegalArgumentException("At most four aperture faces");
        this.limit = limit;
    }

    /** Caller must check the shared capture is active before constructing a face or sample. */
    void observe(long captureId, Face face, Sample sample, boolean cacheMiss,
                 Predicate<String> reserve, BiConsumer<String, String> record) {
        if (capture != captureId) { entries.clear(); capture = captureId; }
        Entry entry = entries.get(face);
        if (entry == null) {
            if (entries.size() >= limit) return;
            entry = new Entry(); entries.put(face, entry);
        }
        entry.observations++;
        if (cacheMiss) entry.misses++;
        if (entry.previous != null && changed(entry.previous, sample)) {
            entry.changes++; entry.lastChange = entry.observations;
            entry.changedFrom = entry.previous; entry.changedTo = sample;
            for (int i = 0; i < COMPONENTS.length; i++) {
                double delta = Math.abs(sample.components[i] - entry.previous.components[i]);
                if (i < 3 || i >= 14 && i < 17) entry.maxPositionDelta = Math.max(entry.maxPositionDelta, delta);
                else entry.maxBasisDelta = Math.max(entry.maxBasisDelta, delta);
            }
        }
        entry.previous = sample;
        String key = "light-cache/" + face.portal + "/" + face.receiver + "/"
            + face.inwardX + "," + face.inwardY + "," + face.inwardZ;
        if (!reserve.test(key)) return;
        record.accept(key, key + " observations=" + entry.observations + " cacheMisses=" + entry.misses
            + " keyChanges=" + entry.changes + " currentCacheMiss=" + cacheMiss
            + " lastChangeAge=" + (entry.lastChange == 0 ? -1 : entry.observations - entry.lastChange)
            + " maxPositionDelta=" + entry.maxPositionDelta + " maxBasisOrExtentDelta=" + entry.maxBasisDelta
            + " receiver=" + sample.receiverIdentity + " source=" + sample.sourceIdentity
            + " lastDelta=" + (entry.changedFrom == null ? "none" : delta(entry.changedFrom, entry.changedTo)));
    }

    private static boolean changed(Sample a, Sample b) {
        if (!a.receiverIdentity.equals(b.receiverIdentity) || !a.sourceIdentity.equals(b.sourceIdentity)) return true;
        for (int i = 0; i < COMPONENTS.length; i++)
            if (Double.doubleToLongBits(a.components[i]) != Double.doubleToLongBits(b.components[i])) return true;
        return false;
    }
    private static String delta(Sample a, Sample b) {
        StringBuilder text = new StringBuilder();
        if (!a.receiverIdentity.equals(b.receiverIdentity)) text.append("receiver:").append(a.receiverIdentity).append("->").append(b.receiverIdentity).append(';');
        if (!a.sourceIdentity.equals(b.sourceIdentity)) text.append("source:").append(a.sourceIdentity).append("->").append(b.sourceIdentity).append(';');
        for (int i = 0; i < COMPONENTS.length; i++) {
            double old = a.components[i], value = b.components[i];
            if (Double.doubleToLongBits(old) != Double.doubleToLongBits(value)) text.append(COMPONENTS[i])
                .append(':').append(Double.toHexString(old)).append("->").append(Double.toHexString(value))
                .append("(d=").append(value - old).append(");");
        }
        return text.toString();
    }
}
