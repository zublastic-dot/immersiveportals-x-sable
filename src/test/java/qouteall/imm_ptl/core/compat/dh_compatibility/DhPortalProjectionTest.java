package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalProjectionTest {
    private Matrix4f projection(boolean reverse) {
        return new Matrix4f().perspective((float)Math.toRadians(70), 1.6f,
            reverse ? 4096f : .1f, reverse ? .1f : 4096f, reverse);
    }
    private float nearDistance(Vector4f clip, boolean reverse) {
        return reverse ? clip.w - clip.z : clip.w + clip.z;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void excludesGeometryBeforePortalButKeepsFarTerrain(boolean reverse) {
        var p = DhPortalProjection.clip(projection(reverse), new Matrix4f(),
            new Vector4f(0, 0, -1, -20), reverse);
        assertNotNull(p);
        assertTrue(nearDistance(p.transform(new Vector4f(0, 0, -10, 1)), reverse) < 0);
        assertEquals(0, nearDistance(p.transform(new Vector4f(0, 0, -20, 1)), reverse), .001f);
        assertTrue(nearDistance(p.transform(new Vector4f(0, 0, -500, 1)), reverse) > 0);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tiltedMovingPlaneRespectsWorldSideAfterCameraRotation(boolean reverse) {
        var mv = new Matrix4f().rotateY(.4f).rotateX(-.3f);
        var plane = new Vector4f(.2f, .1f, -1, -30);
        var original = projection(reverse);
        var p = DhPortalProjection.clip(original, mv, plane, reverse);
        assertNotNull(p);
        for (var point : new Vector4f[]{new Vector4f(2, 3, -10, 1), new Vector4f(2, 3, -100, 1)}) {
            var eye = mv.transform(new Vector4f(point));
            var clip = p.transform(new Vector4f(eye));
            var before = original.transform(new Vector4f(eye));
            assertEquals(Math.signum(plane.dot(point)), Math.signum(nearDistance(clip, reverse)));
            assertEquals(before.x / before.w, clip.x / clip.w, .00001f);
            assertEquals(before.y / before.w, clip.y / clip.w, .00001f);
        }
    }

    @Test void degeneratePlaneDoesNotProduceAnInvalidProjection() {
        assertNull(DhPortalProjection.clip(projection(false), new Matrix4f(), new Vector4f(), false));
    }
}
