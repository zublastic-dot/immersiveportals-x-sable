package qouteall.imm_ptl.core.compat;

import ipl.sable.render.IplDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IplDiagnosticsTest {
    @Test void defaultOffAndExplicitOptInAreCachedWithoutMinecraftLinkage() throws Exception {
        String key = "ipl.diagnostics.verbose";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            Class<?> normal = freshDiagnostics();
            assertEquals(false, normal.getMethod("verbose").invoke(null));
            System.setProperty(key, "true");
            assertEquals(false, normal.getMethod("verbose").invoke(null), "JVM opt-in is cached at initialization");
            Class<?> optedIn = freshDiagnostics();
            assertEquals(true, optedIn.getMethod("verbose").invoke(null));
            System.setProperty(key, "anything-else");
            assertEquals(true, optedIn.getMethod("verbose").invoke(null));
            assertEquals(false, freshDiagnostics().getMethod("verbose").invoke(null));
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    private Class<?> freshDiagnostics() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/ipl/sable/render/IplDiagnostics.class")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        // Bootstrap-only parent proves this common policy cannot load Minecraft/client classes.
        return new ClassLoader(null) {
            Class<?> define() { return defineClass("ipl.sable.render.IplDiagnostics", bytes, 0, bytes.length); }
        }.define();
    }

    @Test void alternatingProgramsNeverRelogPairsOrGrowPastSessionCap() {
        var keys = new IplDiagnostics.BoundedKeys(3);
        assertTrue(keys.first("Chest:1"));
        assertTrue(keys.first("Chest:2"));
        for (int i = 0; i < 100; i++) {
            assertFalse(keys.first("Chest:1"));
            assertFalse(keys.first("Chest:2"));
        }
        assertTrue(keys.first("Cog:1"));
        for (int i = 3; i < 100; i++) assertFalse(keys.first("Chest:" + i));
        assertThrows(IllegalArgumentException.class, () -> new IplDiagnostics.BoundedKeys(0));
        assertThrows(IllegalArgumentException.class, () -> new IplDiagnostics.BoundedKeys(257));
    }
}
