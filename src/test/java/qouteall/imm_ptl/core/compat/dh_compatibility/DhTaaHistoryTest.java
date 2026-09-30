package qouteall.imm_ptl.core.compat.dh_compatibility;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhTaaHistoryTest {
    @Test void advancesOncePerFrameAndUsesPreviousCameraAndMatrices() {
        var h = new DhTaaHistory(); var p = new Matrix4f(); var v = new Matrix4f();
        for (int frame = 0; frame < 20; frame++) {
            var camera = new Vector3d(frame * .1, 0, 0);
            assertEquals(frame > 0, h.begin(frame, frame * 16_000_000L, p, v, camera));
            assertEquals(frame & 7, h.phase()); assertEquals(frame > 0, h.valid);
            if (frame > 0) assertEquals((frame - 1) * .1, h.previousCamera.x, 1e-9);
            h.complete();
            assertFalse(h.begin(frame, frame * 16_000_000L, p, v, camera));
            assertEquals(frame & 7, h.phase());
        }
    }
    @Test void missingFramesLongPauseTeleportProjectionRotationAndFailureDiscardHistory() {
        for (int condition = 0; condition < 7; condition++) {
            var h = new DhTaaHistory(); var p = new Matrix4f(); var v = new Matrix4f(); var c = new Vector3d();
            h.begin(1, 1, p, v, c); if (condition != 6) h.complete();
            if (condition == 2) c.x = 32;
            if (condition == 3) p.scale(2);
            if (condition == 4) v.rotateY(1);
            if (condition == 5) h.invalidate();
            h.begin(condition == 0 ? 3 : 2, condition == 1 ? 1_000_000_000L : 16_000_000L, p, v, c);
            assertFalse(h.valid, "reset condition " + condition); assertEquals(0, h.phase());
        }
    }
}
