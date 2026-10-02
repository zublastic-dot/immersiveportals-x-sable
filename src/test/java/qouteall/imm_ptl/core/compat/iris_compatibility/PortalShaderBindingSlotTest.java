package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PortalShaderBindingSlotTest {
    @Test void repeatedSetupClosesOldBindingBeforeObtainingNewOne() {
        var events = new ArrayList<String>();
        var slot = new PortalShaderBindingSlot();
        slot.bind(() -> { events.add("bind-a");return () -> events.add("close-a"); });
        slot.bind(() -> { events.add("bind-b");return () -> events.add("close-b"); });
        slot.close();slot.close();
        assertEquals(java.util.List.of("bind-a", "close-a", "bind-b", "close-b"), events);
    }

    @Test void differentProgramBeginsBeforeHostSamplerSetupAndOldClearCannotClobberIt() {
        var events = new ArrayList<String>();
        var old = new PortalShaderBindingSlot();var next = new PortalShaderBindingSlot();
        old.bind(() -> () -> events.add("restore-old"));
        next.begin();events.add("host-samplers");
        next.bind(() -> { events.add("bind-next");return () -> events.add("restore-next"); });
        old.close();assertEquals(java.util.List.of("restore-old", "host-samplers", "bind-next"), events);
        PortalShaderBindingSlot.closeCurrent();
        assertEquals(java.util.List.of("restore-old", "host-samplers", "bind-next", "restore-next"), events);
    }

    @Test void failedSetupDoesNotRetainPreviousResource() {
        var events = new ArrayList<String>();var slot = new PortalShaderBindingSlot();
        slot.bind(() -> () -> events.add("closed"));
        assertThrows(IllegalStateException.class, () -> slot.bind(() -> { throw new IllegalStateException(); }));
        slot.close();assertEquals(java.util.List.of("closed"), events);
    }
}
