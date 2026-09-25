package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** CPU-side shader packs have the same lifetime as Iris's compiled dimension pipelines. */
public final class DimensionShaderPacks<K, V> {
    private final Map<K, V> packs = new HashMap<>();

    public void select(K dimension, Supplier<V> build, Consumer<V> activate) {
        V pack = packs.get(dimension);
        if (pack == null) {
            pack = build.get();
            if (pack == null) {
                return;
            }
            // Do not cache a pack whose activation failed.
            activate.accept(pack);
            packs.put(dimension, pack);
        } else {
            activate.accept(pack);
        }
    }

    public void clear() {
        packs.clear();
    }
}
