package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class DhScopedContextTest {
    @Test void nestedViewsRestoreAndExceptionsDoNotLeak() {
        var context = new DhScopedContext<String>();
        assertNull(context.current());
        try (var outer = context.push("nether")) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = context.push("overworld")) {
                    assertEquals("overworld", context.current());
                    throw new IllegalStateException("test failure");
                }
            });
            assertEquals("nether", context.current());
        }
        assertNull(context.current());
    }

    @Test void remoteTickNeverChangesAnotherThreadsPlayerView() {
        var context = new DhScopedContext<String>();
        try (var tick = context.push("remote")) {
            assertNull(CompletableFuture.supplyAsync(context::current).join());
            assertEquals("remote", context.current());
        }
        assertNull(context.current());
    }

    @Test void viewExpiryStopsRequestingUnseenDimensions() {
        assertTrue(DhPortalView.isRecent(42, 42));
        assertTrue(DhPortalView.isRecent(42, 42 + DhPortalView.ACTIVE_NANOS - 1));
        assertFalse(DhPortalView.isRecent(42, 42 + DhPortalView.ACTIVE_NANOS));
        assertFalse(DhPortalView.isRecent(42, 41));
        assertTrue(DhPortalView.isRecent(Long.MAX_VALUE - 2, Long.MIN_VALUE + 2));
    }

    @Test void absentAndUninspectedDhVersionsKeepTheirOwnBehavior() {
        assertTrue(DhCompatibility.supports("3.3.2"));
        assertTrue(DhCompatibility.supports("3.3.2-1.21.1"));
        assertTrue(DhCompatibility.supports("3.3.3"));
        assertTrue(DhCompatibility.supports("3.3.3-1.21.1"));
        for (String version : new String[]{null, "", "2.4.5-b", "3.3.1", "3.3.4", "3.3.2-custom", "3.3.3-custom"}) {
            assertFalse(DhCompatibility.supports(version));
        }
    }
}
