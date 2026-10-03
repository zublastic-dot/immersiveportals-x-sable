package qouteall.imm_ptl.core.teleportation;

/** Implemented by the native Channel; called only on its executor. */
public interface PortalSoundChannelState {
    void portal$setMuted(boolean muted);
}
