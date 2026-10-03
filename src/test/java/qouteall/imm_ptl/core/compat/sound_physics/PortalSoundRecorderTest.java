package qouteall.imm_ptl.core.compat.sound_physics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.compat.sound_physics.PortalSoundRecorder.*;

class PortalSoundRecorderTest {
    @Test void refusesExistingSessionOrHeldKeysWithoutAnyMutation() {
        for (int condition = 0; condition < 3; condition++) {
            Fake backend = new Fake();
            if (condition == 0) backend.status = new Status(true, "owner");
            if (condition == 1) backend.high = true;
            if (condition == 2) backend.extreme = true;
            assertThrows(IllegalStateException.class, () -> Lease.acquire(backend, 0));
            assertEquals(0, backend.writes);
            assertEquals(0, backend.starts);
            assertEquals(0, backend.stops);
        }
    }

    @Test void boundedLeaseRestoresPreviousModeAndReleasesOnlyItsSessionOnce() throws Exception {
        Fake backend = new Fake();
        Lease lease = Lease.acquire(backend, 123);
        assertEquals("HOLD", backend.mode);
        assertTrue(backend.high);
        assertNull(lease.expiryReason(122 + LIMIT_NANOS));
        assertEquals("60-second limit", lease.expiryReason(123 + LIMIT_NANOS));
        assertTrue(lease.close("deadline").success());
        assertEquals("TOGGLE", backend.mode);
        assertFalse(backend.high);
        assertEquals(1, backend.stops);
        lease.close("again");
        assertEquals(1, backend.stops);
    }

    @Test void physicalWorldSwapAndDisconnectEachEndTheLease() throws Exception {
        for (boolean disconnect : new boolean[]{false, true}) {
            Fake backend = new Fake();
            Lease lease = Lease.acquire(backend, 0);
            if (disconnect) backend.connection = null;
            else backend.level = new Object();
            assertEquals("client world changed", lease.expiryReason(1));
            assertTrue(lease.close("world changed").success());
            assertFalse(backend.high);
            assertEquals("TOGGLE", backend.mode);
            assertEquals(1, backend.stops);
        }
    }

    @Test void independentNativeStopRestoresInputsWithoutStoppingAnotherRecorder() throws Exception {
        for (boolean replaced : new boolean[]{false, true}) {
            Fake backend = new Fake();
            Lease lease = Lease.acquire(backend, 0);
            backend.status = new Status(replaced, replaced ? "owner-new" : null);
            backend.mode = "owner-edited-value";
            assertEquals("SPA recording ended or was replaced", lease.expiryReason(1));
            assertTrue(lease.close("native ended").success());
            assertEquals(0, backend.stops);
            assertEquals("owner-edited-value", backend.mode, "Do not overwrite settings changed during capture");
            assertFalse(backend.high);
        }
    }

    @Test void startFailureAndStopFailureStillRestoreOwnedInputs() throws Exception {
        Fake failedStart = new Fake();
        failedStart.startAllowed = false;
        assertThrows(IllegalStateException.class, () -> Lease.acquire(failedStart, 0));
        assertEquals("TOGGLE", failedStart.mode);
        assertFalse(failedStart.high);
        assertEquals(0, failedStart.stops);
        Fake failedStop = new Fake();
        Lease lease = Lease.acquire(failedStop, 0);
        failedStop.failStop = true;
        assertFalse(lease.close("explicit").success());
        assertFalse(failedStop.high);
        assertEquals("TOGGLE", failedStop.mode);
    }

    @Test void userRecorderInputChangeTerminatesCapture() throws Exception {
        Fake backend = new Fake();
        Lease lease = Lease.acquire(backend, 0);
        backend.high = false;
        assertEquals("recorder input changed", lease.expiryReason(1));
        lease.close("input changed");
        assertFalse(backend.high);
        assertEquals("TOGGLE", backend.mode);
    }

    @Test void statusFailureAfterSuccessfulStartStopsUnpublishedCaptureAndRestoresInputs() {
        Fake backend = new Fake();
        backend.failStatusAfterStart = true;
        assertThrows(ReflectiveOperationException.class, () -> Lease.acquire(backend, 0));
        assertEquals(1, backend.starts);
        assertEquals(1, backend.stops);
        assertFalse(backend.status.active());
        assertFalse(backend.high);
        assertEquals("TOGGLE", backend.mode);
    }

    @Test void installedSpaPublicApiHasHighCaptureBoundAndNonSavingRuntimeSetter() throws Exception {
        String configured = System.getProperty("ip.portal.test.spaJar");
        if (configured == null || configured.isBlank()) {
            System.out.println("NOT_EXECUTED installed SPA bounded recorder API: set ip.portal.test.spaJar");
            return;
        }
        Api api = Api.load(getClass().getClassLoader());
        assertEquals(Path.of(configured).toRealPath(), Path.of(api.startBounded().getDeclaringClass()
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        ClassNode service = node("com/sonicether/soundphysics/flightrecorder/SpraFlightRecorderService");
        assertEquals(HIGH_KEY, service.fields.stream().filter(f -> f.name.equals("HIGH_KEY_NAME")).findFirst().orElseThrow().value);
        assertEquals(EXTREME_KEY, service.fields.stream().filter(f -> f.name.equals("EXTREME_KEY_NAME")).findFirst().orElseThrow().value);
        MethodNode bounded = service.methods.stream().filter(m -> m.name.equals("startBounded")).findFirst().orElseThrow();
        assertTrue((bounded.access & Opcodes.ACC_PUBLIC) != 0);
        List<Object> constants = new ArrayList<>();
        for (var insn : bounded.instructions) if (insn instanceof LdcInsnNode constant) constants.add(constant.cst);
        assertTrue(constants.contains(LIMIT_NANOS), "Installed native recorder also enforces a 60-second bound");
        Class<?> mode = Class.forName("com.sonicether.soundphysics.flightrecorder.SpraFlightRecorderMode");
        Object high = mode.getMethod("valueOf", String.class).invoke(null, "HIGH");
        assertEquals(true, mode.getMethod("capturesAcousticEvents").invoke(high));

        ClassNode entry = node("de/maxhenkel/sound_physics_remastered/configbuilder/entry/AbstractConfigEntry");
        for (MethodNode method : entry.methods) if (method.name.equals("set") || method.name.equals("syncEntryToProperties")) {
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call) {
                assertNotEquals("save", call.name);
                assertNotEquals("saveSync", call.name);
                assertFalse(call.owner.startsWith("java/nio/file/") || call.owner.startsWith("java/io/"));
            }
        }
        System.out.println("EXECUTED installed SPA bounded recorder API: HIGH mode, public access, native deadline, no persistent config save");
    }

    private static ClassNode node(String name) throws IOException {
        try (var input = PortalSoundRecorderTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static final class Fake implements Backend {
        Status status = new Status(false, null);
        String mode = "TOGGLE";
        boolean high, extreme, failStop, failStatusAfterStart, startAllowed = true;
        Object level = new Object(), connection = new Object();
        int starts, stops, writes;
        public Status status() throws ReflectiveOperationException {
            if (failStatusAfterStart && starts > 0) throw new ReflectiveOperationException("test status failure");
            return status;
        }
        public Result startBounded() {
            starts++;
            if (!startAllowed) return new Result(false, "test start failure");
            status = new Status(true, "ours");
            return new Result(true, "started");
        }
        public Result stop() throws ReflectiveOperationException {
            stops++;
            if (failStop) throw new ReflectiveOperationException("test stop failure");
            status = new Status(false, null);
            return new Result(true, "stopped");
        }
        public String highMode() { return mode; }
        public void highMode(String value) { writes++; mode = value; }
        public boolean highDown() { return high; }
        public void highDown(boolean value) { writes++; high = value; }
        public boolean extremeDown() { return extreme; }
        public Object level() { return level; }
        public Object connection() { return connection; }
    }
}
