package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.function.Supplier;
import qouteall.imm_ptl.core.lighting.PortalLightGpu;

/** Render-thread single owner; a late clear of an older shader cannot restore over a newer draw. */
public final class PortalShaderBindingSlot {
    private static PortalShaderBindingSlot active;
    private PortalLightGpu.Binding current;

    /** Run before Iris changes the next program's sampler bindings. */
    public void begin() { closeCurrent(); }

    public static void closeCurrent() {
        if (active != null) active.close();
    }

    public void close() {
        if (active != this) return;
        active = null;
        var previous = current;
        current = null;
        if (previous != null) previous.close();
    }

    public void bind(Supplier<PortalLightGpu.Binding> supplier) {
        begin();
        current = supplier.get();
        active = this;
    }
}
