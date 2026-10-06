package dev.ryanhcode.sable.api.physics.callback;

/** TEST-ONLY shape of the method called from JNI; no Minecraft callback adapter. */
public interface BlockSubLevelCollisionCallback {
    double[] onCollision(int x, int y, int z, int otherX, int otherY, int otherZ,
                         double localX, double localY, double localZ, double velocity,
                         boolean hasOtherBlock);
}
