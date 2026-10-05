package qouteall.imm_ptl.core.lighting;

/** Exact-world/engine handoff; no timers or guessed propagation delays. */
final class PortalColorWarmup<W> {
    private record State<W>(W world,Object engine,long generation) {}
    private volatile State<W> current;
    private long generation;
    synchronized void begin(W world,Object engine) { current=world==null || engine==null ? null : new State<>(world,engine,++generation); }
    boolean applies(W world) { State<W> state=current; return state!=null && state.world==world; }
    long token(W world,Object engine) {
        State<W> state=current;
        return state!=null && state.world==world && state.engine==engine ? state.generation : 0;
    }
    synchronized void complete(W world,Object engine,long token) {
        State<W> state=current;
        if (state!=null && state.world==world && state.engine==engine && state.generation==token) current=null;
    }
    synchronized void clear() { current=null; }
    long generation() { State<W> state=current; return state==null ? 0 : state.generation; }
    static int combine(int stationary,int dynamic) {
        return Math.max(stationary>>>16 & 255,dynamic>>>16 & 255)<<16
            | Math.max(stationary>>>8 & 255,dynamic>>>8 & 255)<<8 | Math.max(stationary & 255,dynamic & 255);
    }
}
