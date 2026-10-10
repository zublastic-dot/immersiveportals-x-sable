package ipl.sable.natives;

/** TEST-ONLY JNI subset; runner compares descriptors to the production source. */
public final class IplRapierNatives {
    private IplRapierNatives() {}
    public static native boolean setParentFrame(long scene, int body, int frame);
    public static native long createImageCollider(long scene, int body, double x, double y, double z,
                                                 double qx, double qy, double qz, double qw);
    public static native void removeImageCollider(long scene, int body, long image);
}
