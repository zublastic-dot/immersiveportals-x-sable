import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import ipl.sable.natives.IplRapierNatives;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless real-native assertions. No mocks of physics, but explicit JNI Java stubs. */
public final class NativeSmoke {
    private static final double EPS = 0.002;
    private static final AtomicInteger CALLBACKS = new AtomicInteger();
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
            message + ": expected " + expected + ", got " + actual);
    }
    public static final class ContactCallback implements BlockSubLevelCollisionCallback {
        @Override public double[] onCollision(int x, int y, int z, int ox, int oy, int oz,
                                             double lx, double ly, double lz, double velocity, boolean other) {
            CALLBACKS.incrementAndGet();
            return new double[] {0, 0, 0, 0};
        }
    }
    private static int cubeCollider(BlockSubLevelCollisionCallback callback) {
        int collider = Rapier3D.newVoxelCollider(0.7, 1.0, 0.0, false, callback);
        check(collider >= 0 && collider < 65536, "five-argument newVoxelCollider returns encodable id");
        Rapier3D.addVoxelColliderBox(collider, new double[] {0, 0, 0, 1, 1, 1});
        return collider;
    }
    // Chunk encoding reserves zero for no collider; registration returns a zero-based index.
    private static int voxel(int collider) { return ((collider + 1) << 16) | 3; } // VoxelPhysicsState.Corner
    private static void body(long scene, int id, int collider, double x, double y, double z) {
        Rapier3D.createSubLevel(scene, id, new double[] {x, y, z, 0, 0, 0, 1});
        Rapier3D.setCenterOfMass(scene, id, 0.5, 0.5, 0.5);
        Rapier3D.setLocalBounds(scene, id, 0, 0, 0, 0, 0, 0);
        int[] blocks = new int[4096];
        blocks[0] = voxel(collider);
        Rapier3D.addChunk(scene, 0, 0, 0, blocks, false, id);
        Rapier3D.setMassProperties(scene, id, 1.0, new double[] {0.5, 0.5, 0.5},
            new double[] {1.0 / 6, 0, 0, 0, 1.0 / 6, 0, 0, 0, 1.0 / 6});
    }
    private static double[] pose(long scene, int body) {
        double[] result = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        Rapier3D.getPose(scene, body, result);
        check(Double.isFinite(result[0]) && Double.isFinite(result[1]) && Double.isFinite(result[2]),
            "body pose exists and is finite");
        return result;
    }
    private static void sharedStepAndLifecycle() {
        long first = Rapier3D.initialize(0, 0, 0, 0);
        long hosting = Rapier3D.initialize(0, 0, 0, 0);
        long last = Rapier3D.initialize(0, 0, 0, 0);
        check(first != 0 && hosting != 0 && last != 0, "three distinct chart handles allocated");
        check(first != hosting && hosting != last && first != last, "chart handles are distinct");
        boolean bodyCreated = false;
        try {
            int cube = cubeCollider(null);
            body(hosting, 101, cube, 32, 10, 32);
            bodyCreated = true;
            check(IplRapierNatives.setParentFrame(hosting, 101, 1), "Atlas body tag succeeds");
            Rapier3D.addLinearAngularVelocities(hosting, 101, 2, 0, 0, 0, 0, 0, true);
            near(pose(last, 101)[0], 32, EPS, "another chart sees initial hosted body");
            Rapier3D.step(last, 0.125);
            near(pose(hosting, 101)[0], 32.25, EPS, "one final-chart step advances middle-chart body exactly once");
            Rapier3D.step(first, 0.125);
            near(pose(last, 101)[0], 32.5, EPS, "a different chart advances the same world once");
            long rope = Rapier3D.createRope(first, 0.05, 1, new double[] {50, 10, 50, 51, 10, 50}, 2);
            check(rope >= 0, "rope created");
            double[] points = Rapier3D.queryRope(first, rope);
            check(points != null && points.length >= 6, "rope JNI readback returns both points");
            // Stock Java discards this long. Zero is the corrected deterministic ABI result,
            // not a newly invented public success protocol.
            check(Rapier3D.removeRope(first, rope) == 0L, "removeRope matches Java long return ABI");
            Rapier3D.step(last, 0.125);
            near(pose(hosting, 101)[0], 32.75, EPS, "world still steps after rope removal");
            System.out.println("CASE shared-step-and-rope PASS");
        } finally {
            if (bodyCreated) Rapier3D.removeSubLevel(hosting, 101);
            Rapier3D.dispose(last);
            Rapier3D.dispose(hosting);
            Rapier3D.dispose(first);
        }
    }
    private static void imageTerrainAndIsolation() {
        // Different gravity after all prior handles were disposed also checks that
        // the old shared world did not survive teardown through a leaked strong reference.
        long parent = Rapier3D.initialize(0, -10, 0, 0);
        long hosting = Rapier3D.initialize(0, -10, 0, 0);
        long unrelated = Rapier3D.initialize(0, -10, 0, 0);
        long image = -1;
        int created = 0;
        try {
            int collider = cubeCollider(new ContactCallback());
            int[] floor = new int[4096];
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) floor[x + (z << 4)] = voxel(collider);
            Rapier3D.addChunk(parent, 0, 0, 0, floor, true, -1);
            body(hosting, 201, collider, 6, 5, 6); created = 1;
            body(hosting, 202, collider, 11, 5, 11); created = 2;
            check(IplRapierNatives.setParentFrame(hosting, 201, 1), "image body parent tag");
            check(IplRapierNatives.setParentFrame(hosting, 202, 2), "control body separate parent tag");
            image = IplRapierNatives.createImageCollider(parent, 201, 0, 0, 0, 0, 0, 0, 1);
            check(image != -1, "cross-chart image collider created");
            for (int i = 0; i < 240; i++) Rapier3D.step(unrelated, 1.0 / 120);
            double[] landed = pose(hosting, 201);
            double[] isolated = pose(hosting, 202);
            check(landed[1] > 1.35 && landed[1] < 1.7, "image body lands on parent terrain: y=" + landed[1]);
            check(isolated[1] < -10, "no-image control falls through foreign chart terrain: y=" + isolated[1]);
            near(landed[0], 6, 0.05, "image contact does not shift body sideways");
            check(CALLBACKS.get() > 0, "Java collision callback was invoked through exact JNI descriptor");
            System.out.println("CASE image-terrain-isolation PASS landedY=" + landed[1]
                + " controlY=" + isolated[1] + " callbackCalls=" + CALLBACKS.get());
        } finally {
            if (image != -1) IplRapierNatives.removeImageCollider(parent, 201, image);
            if (created >= 2) Rapier3D.removeSubLevel(hosting, 202);
            if (created >= 1) Rapier3D.removeSubLevel(hosting, 201);
            Rapier3D.removeChunk(parent, 0, 0, 0, true);
            Rapier3D.dispose(unrelated);
            Rapier3D.dispose(hosting);
            Rapier3D.dispose(parent);
        }
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("absolute native library path required");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        check(!IplRapierNatives.setParentFrame(0, -1, 0), "harmless Atlas capability probe");
        sharedStepAndLifecycle();
        imageTerrainAndIsolation();
        System.out.println("NATIVE_SMOKE_PASS assertions=" + assertions + " callbackCalls=" + CALLBACKS.get());
    }
}
