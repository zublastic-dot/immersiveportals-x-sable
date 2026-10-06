package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;

/**
 * TEST-ONLY JNI declarations, not the production Sable class. The runner verifies
 * every descriptor against the supplied Sable JAR (including nested JARs).
 * Access is widened only so the standalone harness needs no game/loader classes.
 */
public final class Rapier3D {
    private Rapier3D() {}
    public static native long initialize(double x, double y, double z, double drag);
    public static native void dispose(long scene);
    public static native void tick(long scene, double dt);
    public static native void step(long scene, double dt);
    public static native void createSubLevel(long scene, int id, double[] pose);
    public static native void removeSubLevel(long scene, int id);
    public static native void setCenterOfMass(long scene, int id, double x, double y, double z);
    public static native void setLocalBounds(long scene, int id, int x0, int y0, int z0, int x1, int y1, int z1);
    public static native void setMassProperties(long scene, int id, double mass, double[] center, double[] inertia);
    public static native void addChunk(long scene, int x, int y, int z, int[] data, boolean global, int id);
    public static native void removeChunk(long scene, int x, int y, int z, boolean global);
    public static native void getPose(long scene, int id, double[] pose);
    public static native void getLinearVelocity(long scene, int id, double[] velocity);
    public static native void addLinearAngularVelocities(long scene, int id, double x, double y, double z,
                                                       double ax, double ay, double az, boolean wake);
    public static native int newVoxelCollider(double friction, double volume, double restitution,
                                              boolean fluid, BlockSubLevelCollisionCallback callback);
    public static native void addVoxelColliderBox(int id, double[] bounds);
    public static native long createRope(long scene, double radius, double firstLength, double[] points, int count);
    public static native long removeRope(long scene, long rope);
    public static native double[] queryRope(long scene, long rope);
}
