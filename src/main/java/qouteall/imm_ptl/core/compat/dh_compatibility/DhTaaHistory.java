package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Render-thread temporal bookkeeping, independent of Minecraft and GPU allocation. */
public final class DhTaaHistory {
    private int frame;
    private long time;
    private boolean begun, completed;
    private int phase;
    private Matrix4f projection, view;
    private Vector3d camera;
    public Matrix4f previousCombined = new Matrix4f();
    public Vector3d previousCamera = new Vector3d();
    public boolean valid;
    private Snapshot snapshot;
    public record Snapshot(int frame, long time, int phase, Matrix4f projection, Matrix4f view, Vector3d camera) {
        public Matrix4f combined() { return new Matrix4f(projection).mul(view); }
        public boolean matches(int nextFrame, long now, Matrix4f nextProjection, Matrix4f nextView, Vector3d nextCamera) {
            return nextFrame - frame >= 0 && nextFrame - frame <= 1 && now >= time && now - time < 500_000_000L
                && camera.distanceSquared(nextCamera) < 64 && close(projection, nextProjection, .001f)
                && close(view, nextView, .25f);
        }
    }

    /** Returns true when the previous completed output becomes this frame's history. */
    public boolean begin(int nextFrame, long now, Matrix4f nextProjection, Matrix4f nextView, Vector3d nextCamera) {
        if (begun && frame == nextFrame) return false;
        snapshot = null;
        valid = begun && completed && nextFrame - frame == 1 && now - time >= 0
            && now - time < 500_000_000L && camera.distanceSquared(nextCamera) < 64
            && close(projection, nextProjection, .001f) && close(view, nextView, .25f);
        if (valid) {
            previousCombined.set(projection).mul(view);
            previousCamera.set(camera);
            phase = (phase + 1) & 7;
        } else {
            previousCombined.set(nextProjection).mul(nextView);
            previousCamera.set(nextCamera);
            phase = 0;
        }
        boolean promote = completed;
        frame = nextFrame; time = now; begun = true; completed = false;
        projection = new Matrix4f(nextProjection); view = new Matrix4f(nextView); camera = new Vector3d(nextCamera);
        return promote;
    }

    private static boolean close(Matrix4f a, Matrix4f b, float tolerance) {
        float[] av = a.get(new float[16]), bv = b.get(new float[16]);
        for (int i = 0; i < 16; i++) if (!Float.isFinite(av[i]) || !Float.isFinite(bv[i]) || Math.abs(av[i] - bv[i]) > tolerance) return false;
        return true;
    }
    public int phase() { return phase; }
    public Snapshot snapshot() { return snapshot; }
    /** Install a completed external image before beginning the next portal frame. */
    public void restoreCompleted(Snapshot source) {
        frame = source.frame(); time = source.time(); phase = source.phase();
        projection = new Matrix4f(source.projection()); view = new Matrix4f(source.view()); camera = new Vector3d(source.camera());
        begun = completed = valid = true;
        previousCombined.set(source.combined()); previousCamera.set(camera);
        snapshot = new Snapshot(frame, time, phase, new Matrix4f(projection), new Matrix4f(view), new Vector3d(camera));
    }
    public void complete() {
        completed = true;
        snapshot = new Snapshot(frame, time, phase, new Matrix4f(projection), new Matrix4f(view), new Vector3d(camera));
    }
    public void invalidate() { begun = false; completed = false; valid = false; snapshot = null; }
}
