package qouteall.imm_ptl.core.compat.iris_compatibility;

import ipl.sable.client.IplParentSyncDiagnostics;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IplParentSyncDiagnosticsTest {
    private static final UUID SHIP = new UUID(0, 42);

    @Test void freshRestampsUpdateLatestParentWithoutExtendingTheOriginalDeadline() {
        var pending = new IplParentSyncDiagnostics.PendingStamp("minecraft:overworld", 1_000);
        for (long now = 6_000; now <= 31_000; now += 5_000) {
            pending = pending.withLatestParent("mod:destination_" + now);
            assertEquals(1_000, pending.queuedAtMs());
            assertFalse(pending.expired(now));
        }
        assertEquals("mod:destination_31000", pending.parentDimId());
        assertTrue(pending.expired(31_001));
        assertFalse(pending.expired(0), "Clock rollback cannot create an immediate expiry");
    }

    @Test void sixHundredMissingAllocationRetriesProduceTwoWarningsAndOneExpiry() {
        var diagnostics = new IplParentSyncDiagnostics();
        var notices = new ArrayList<IplParentSyncDiagnostics.Notice>();
        for (long now = 0; now <= 30_000; now += 50) {
            var notice = diagnostics.missing(SHIP, "minecraft:overworld", "client allocation absent", now);
            if (notice != null) notices.add(notice);
        }
        assertEquals(2, notices.size());
        assertEquals("allocation delayed", notices.get(0).phase());
        assertEquals(1_000, notices.get(0).missingForMs());
        assertEquals("allocation stalled", notices.get(1).phase());
        assertEquals(5_000, notices.get(1).missingForMs());
        var expiry = diagnostics.expired(SHIP, "minecraft:overworld", 30_001);
        assertEquals("retry expired", expiry.phase());
        assertEquals(SHIP, expiry.id());
        assertEquals(601, expiry.lookups());
        assertEquals("client allocation absent", expiry.reason());
    }

    @Test void recurringFailedAttemptsRetainIdentityButDoNotFloodExpiryWarnings() {
        var diagnostics = new IplParentSyncDiagnostics();
        diagnostics.missing(SHIP, "minecraft:overworld", "client hosting container absent", 0);
        assertNotNull(diagnostics.expired(SHIP, "minecraft:overworld", 30_001));
        for (long now = 60_002; now < 330_001; now += 30_001)
            assertNull(diagnostics.expired(SHIP, "minecraft:the_nether", now));
        var repeated = diagnostics.expired(SHIP, "minecraft:the_nether", 330_001);
        assertNotNull(repeated);
        assertEquals(SHIP, repeated.id());assertEquals("minecraft:the_nether", repeated.parent());
        assertEquals(330_001, repeated.missingForMs());
    }

    @Test void normalAllocationRaceIsQuietAndARealRecoveryResetsTheDiagnosticLifecycle() {
        var diagnostics = new IplParentSyncDiagnostics();
        assertNull(diagnostics.missing(SHIP, "a", "allocation absent", 0));
        assertNull(diagnostics.missing(SHIP, "a", "allocation absent", 999));
        diagnostics.recovered(SHIP);
        assertEquals(0, diagnostics.trackedIdentities());
        assertNull(diagnostics.missing(SHIP, "b", "allocation absent", 10_000));
        var first = diagnostics.missing(SHIP, "b", "allocation absent", 11_000);
        assertEquals("allocation delayed", first.phase());assertEquals(1_000, first.missingForMs());
        diagnostics.clear();assertEquals(0, diagnostics.trackedIdentities());
    }

    @Test void diagnosticIdentityStorageIsBounded() {
        var diagnostics = new IplParentSyncDiagnostics();
        for (int i = 0; i < 10_000; i++) diagnostics.missing(new UUID(0, i), "parent", "missing", i);
        assertEquals(256, diagnostics.trackedIdentities());
    }

    @Test void actualParentStampQueueUsesPreservedDeadlineAndLookupUsesBoundedDiagnostic() throws Exception {
        var outer = node("ipl/sable/client/IplParentDimSync");
        boolean preserves = false, expiry = false;
        for (var method : outer.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) {
                preserves |= call.owner.endsWith("IplParentSyncDiagnostics$PendingStamp") && call.name.equals("withLatestParent");
                expiry |= call.owner.endsWith("IplParentSyncDiagnostics$PendingStamp") && call.name.equals("expired");
            }
        assertTrue(preserves);assertTrue(expiry);
        var remote = node("ipl/sable/client/IplParentDimSync$RemoteCallables");
        var lookup = remote.methods.stream().filter(m -> m.name.equals("findHostedSubLevel")).findFirst().orElseThrow();
        boolean bounded = false, recovery = false;
        for (var instruction : lookup.instructions) if (instruction instanceof MethodInsnNode call) {
            assertFalse(call.owner.equals("org/slf4j/Logger") && call.name.equals("warn"), "Tick retry must not log directly");
            bounded |= call.owner.endsWith("IplParentSyncDiagnostics") && call.name.equals("missing");
            recovery |= call.owner.endsWith("IplParentSyncDiagnostics") && call.name.equals("recovered");
        }
        assertTrue(bounded);assertTrue(recovery);
    }

    private static ClassNode node(String path) throws Exception {
        try (var in = IplParentSyncDiagnosticsTest.class.getResourceAsStream("/" + path + ".class")) {
            assertNotNull(in);var node = new ClassNode();new ClassReader(in.readAllBytes()).accept(node, 0);return node;
        }
    }
}
