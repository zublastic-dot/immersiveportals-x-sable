package qouteall.imm_ptl.core.compat.dh_compatibility;

/** A thread-confined override, never a temporary change to DH's actual-player cache. */
public final class DhScopedContext<T> {
    private final ThreadLocal<T> value = new ThreadLocal<>();

    public T current() { return value.get(); }

    public Scope push(T next) {
        T previous = value.get();
        value.set(next);
        return () -> {
            if (previous == null) value.remove();
            else value.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
