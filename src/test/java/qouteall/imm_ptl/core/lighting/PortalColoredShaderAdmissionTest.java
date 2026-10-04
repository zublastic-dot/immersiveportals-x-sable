package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalColoredShaderAdmissionTest {
    @Test void admissionCannotLeakAcrossWorldInstancesOrShaderPacks() {
        var admission = new PortalColoredShaderAdmission<Object>();
        Object overworld = new Object(), replacement = new Object(), custom = new Object();
        assertFalse(admission.allows(overworld, "supported"));
        admission.observe(overworld, "supported", 7, true);
        assertTrue(admission.allows(overworld, "supported"));
        assertFalse(admission.allows(replacement, "supported"));
        assertFalse(admission.allows(custom, "supported"));
        assertFalse(admission.allows(overworld, "unsupported"));
        admission.observe(custom, "supported", 11, false);
        assertFalse(admission.allows(custom, "supported"));
        assertTrue(admission.allows(overworld, "supported"));
    }

    @Test void oneSupportedProgramCannotHideAnUnsupportedTerrainPass() {
        var admission = new PortalColoredShaderAdmission<Object>();
        Object world = new Object();
        admission.observe(world, "pack", 1, true);
        admission.observe(world, "pack", 2, false);
        admission.observe(world, "pack", 3, true);
        assertFalse(admission.allows(world, "pack"));
        admission.clear();
        assertFalse(admission.allows(world, "pack"));
        admission.observe(world, "pack", 1, true); // GL reuses a handle after shader reload.
        assertTrue(admission.allows(world, "pack"));
    }

    @Test void worldIdentityWinsOverEqualityAndUnknownProgramsFailClosed() {
        var admission = new PortalColoredShaderAdmission<String>();
        String oldWorld = new String("custom:moon"), newWorld = new String("custom:moon");
        admission.observe(oldWorld, "pack", 0, true);
        assertFalse(admission.allows(oldWorld, "pack"));
        for (int program = 1; program <= 64; program++) admission.observe(oldWorld, "pack", program, true);
        assertTrue(admission.allows(oldWorld, "pack"));
        assertFalse(admission.allows(newWorld, "pack"));
        admission.observe(oldWorld, "pack", 65, true);
        assertFalse(admission.allows(oldWorld, "pack"));
    }
}
