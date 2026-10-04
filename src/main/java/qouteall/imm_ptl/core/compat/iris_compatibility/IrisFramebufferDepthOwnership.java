package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;

/** Depth ownership is independent of Iris's ownership of the framebuffer object. */
public final class IrisFramebufferDepthOwnership<T> {
    private final Set<T> externalDepth = Collections.newSetFromMap(new IdentityHashMap<>());

    public void external(T framebuffer) {
        if (framebuffer != null) externalDepth.add(framebuffer);
    }

    public void updateMainDepth(T framebuffer, int texture, BiConsumer<T, Integer> attach) {
        if (!externalDepth.contains(framebuffer)) attach.accept(framebuffer, texture);
    }

    public void remove(T framebuffer) {
        externalDepth.remove(framebuffer);
    }

    public void clear() {
        externalDepth.clear();
    }
}
