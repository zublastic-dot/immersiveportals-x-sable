package qouteall.imm_ptl.core.lighting;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalSourceShadowTest {
    private static PortalSourceShadow.Capture input(Matrix4f modelView, Matrix4f projection) {
        return new PortalSourceShadow.Capture(1, 2048, Vec3.ZERO, modelView, projection, 96, 1, .25f, 0, 9000);
    }
    @Test void onlyFiniteInvertibleOrthographicMatricesAreAdmitted() {
        assertTrue(input(new Matrix4f(), new Matrix4f().setOrthoSymmetric(192, 192, -256, 256)).valid());
        assertFalse(input(new Matrix4f().zero(), new Matrix4f()).valid());
        assertFalse(input(new Matrix4f(), new Matrix4f().zero()).valid());
        assertFalse(input(new Matrix4f().m00(Float.NaN), new Matrix4f()).valid());
        assertFalse(input(new Matrix4f(), new Matrix4f().perspective(1, 1, 1, 100)).valid());
        assertEquals("nonorthographic-shadow-projection", input(new Matrix4f(), new Matrix4f().perspective(1, 1, 1, 100)).rejection());
        assertEquals("noninvertible-shadow-transform", input(new Matrix4f().zero(), new Matrix4f()).rejection());
    }
    @Test void nativeResolutionAndStorageBoundsAreExplicit() {
        assertEquals(64L * 1024 * 1024, PortalSourceShadow.MAX_BYTES);
        assertEquals(4096, PortalSourceShadow.MAX_RESOLUTION);
        assertEquals(4, PortalSourceShadow.MAX_WORLDS);
        assertThrows(IllegalArgumentException.class, () -> new PortalSourceShadow.Store(5, PortalSourceShadow.MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> new PortalSourceShadow.Store(4, PortalSourceShadow.MAX_BYTES + 1));
        assertFalse(new PortalSourceShadow.Capture(1, 8192, Vec3.ZERO, new Matrix4f(), new Matrix4f(), 96, 1, .25f, 0, 0).valid());
        assertFalse(new PortalSourceShadow.Capture(1, 2048, new Vec3(Double.NaN, 0, 0), new Matrix4f(), new Matrix4f(), 96, 1, .25f, 0, 0).valid());
        assertFalse(new PortalSourceShadow.Capture(1, 2048, Vec3.ZERO, new Matrix4f(), new Matrix4f(), 0, 1, .25f, 0, 0).valid());
    }
}
