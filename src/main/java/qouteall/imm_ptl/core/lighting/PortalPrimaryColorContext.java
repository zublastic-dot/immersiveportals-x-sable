package qouteall.imm_ptl.core.lighting;

/** Immutable identity handoff from the client thread to Colorful's native producer and mesh workers. */
final class PortalPrimaryColorContext<W,A> {
    private record Context<W,A>(W world,A accessor) {}
    private volatile Context<W,A> current;
    void publish(W world,A accessor) { current=new Context<>(world,accessor); }
    void clear() { current=null; }
    A select(W actualPlayerWorld) {
        Context<W,A> context=current;
        return context!=null && actualPlayerWorld==context.world ? context.accessor : null;
    }
}
