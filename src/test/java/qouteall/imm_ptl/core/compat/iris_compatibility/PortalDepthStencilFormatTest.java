package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;

class PortalDepthStencilFormatTest {
    @Test void uiPackedFloatDepthReplacesIncompatiblePortalStencilStorage() {
        var formats = new ArrayList<Integer>();
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, GL_DEPTH24_STENCIL8, formats::add));
        assertEquals(List.of(GL_DEPTH32F_STENCIL8), formats);
    }

    @Test void compatibilityRendererDepthOnlyTargetAlsoGetsMatchingStorage() {
        var formats = new ArrayList<Integer>();
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, GL_DEPTH_COMPONENT24, formats::add));
        assertEquals(List.of(GL_DEPTH32F_STENCIL8), formats);
    }

    @Test void matchingFormatsNeverDiscardExistingDepthOrStencil() {
        for (int format : List.of(GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8)) {
            assertFalse(PortalDepthStencilFormat.matchPackedSource(format, format, ignored -> fail("reallocated")));
        }
    }

    @Test void stableFramesAllocateOnceButInPlaceFormatChangesAreDetected() {
        var destination = new AtomicInteger(GL_DEPTH24_STENCIL8);
        var allocations = new AtomicInteger();
        for (int frame = 0; frame < 120; frame++) {
            PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, destination.get(), value -> {
                allocations.incrementAndGet();
                destination.set(value);
            });
        }
        assertEquals(1, allocations.get());
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH24_STENCIL8, destination.get(), destination::set));
        assertEquals(GL_DEPTH24_STENCIL8, destination.get());
    }

    @Test void resizeRecreatedStorageIsRepairedAgain() {
        var destination = new AtomicInteger(GL_DEPTH_COMPONENT24);
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, destination.get(), destination::set));
        destination.set(GL_DEPTH_COMPONENT24);
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, destination.get(), destination::set));
    }

    @Test void depthOnlyAndUnknownInputsKeepExistingRendererPolicy() {
        for (int format : List.of(GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT24, 0)) {
            assertFalse(PortalDepthStencilFormat.matchPackedSource(format, GL_DEPTH24_STENCIL8, ignored -> fail("reallocated")));
        }
    }

    @Test void allocationFailureIsVisibleAndCanBeRetried() {
        assertThrows(IllegalStateException.class, () -> PortalDepthStencilFormat.matchPackedSource(
            GL_DEPTH32F_STENCIL8, GL_DEPTH24_STENCIL8, ignored -> { throw new IllegalStateException("allocation failed"); }
        ));
        var destination = new AtomicInteger(GL_DEPTH24_STENCIL8);
        assertTrue(PortalDepthStencilFormat.matchPackedSource(GL_DEPTH32F_STENCIL8, destination.get(), destination::set));
    }
}
