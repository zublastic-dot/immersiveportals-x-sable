package qouteall.imm_ptl.core.compat.dh_compatibility;

/** Stored on the existing DH level, so closing it also releases our view. */
public interface DhPortalLevel {
    DhPortalView ip_getDhView();
    void ip_setDhView(DhPortalView view);
    boolean ip_markDhBuffersReported();
}
