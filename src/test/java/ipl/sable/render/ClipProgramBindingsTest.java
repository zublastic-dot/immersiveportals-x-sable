package ipl.sable.render;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClipProgramBindingsTest {
    private static final ClipProgramBindings.Locations TERRAIN =
        new ClipProgramBindings.Locations(4, 5, 6);
    private static final ClipProgramBindings.Locations COPY =
        new ClipProgramBindings.Locations(-1, -1, -1);

    private static final class Graphics implements ClipProgramBindings.Driver {
        final boolean[] enabled = new boolean[3];
        final Map<Integer, ClipProgramBindings.Locations> linked = new HashMap<>();
        int queries;

        public int uniformLocation(int program, String name) {
            queries++;
            var locations = linked.get(program);
            return switch (name) {
                case "iportal_ClippingEquation" -> locations.portal();
                case "ipl_subLevelClipEquation" -> locations.subLevel();
                case "ipl_subLevelClipEquation[1]" -> locations.secondSubLevel();
                default -> throw new AssertionError(name);
            };
        }

        public void clipDistance(int slot, boolean value) {
            enabled[slot] = value;
        }

        void expect(boolean a, boolean b, boolean c) {
            assertArrayEquals(new boolean[]{a, b, c}, enabled);
        }
    }

    @Test void portalTerrainThenCompositeThenTerrainDoesNotClipTheComposite() {
        var gl = new Graphics();
        var bindings = new ClipProgramBindings(gl);
        bindings.configure(TERRAIN, true, false);
        gl.expect(true, false, false);
        // PortalRendering is still true while Iris composites the remote view.
        bindings.configure(COPY, true, false);
        gl.expect(false, false, false);
        bindings.configure(TERRAIN, true, false);
        gl.expect(true, false, false);
    }

    @Test void bothIndependentShipCutsSurviveInterveningCopyPasses() {
        var gl = new Graphics();
        var bindings = new ClipProgramBindings(gl);
        bindings.configure(TERRAIN, true, true);
        gl.expect(true, true, true);
        bindings.configure(COPY, true, true);
        gl.expect(false, false, false);
        bindings.configure(TERRAIN, true, true);
        gl.expect(true, true, true);
        bindings.configure(TERRAIN, true, false);
        gl.expect(true, false, false);
        bindings.configure(TERRAIN, false, false);
        gl.expect(false, false, false);
    }

    @Test void optimizedOutSecondCutIsNeverEnabled() {
        var gl = new Graphics();
        new ClipProgramBindings(gl).configure(
            new ClipProgramBindings.Locations(0, 1, -1), true, true);
        gl.expect(true, true, false);
    }

    @Test void reassertsHardwareStateAfterExternalPostEffectChangesIt() {
        var gl = new Graphics();
        var bindings = new ClipProgramBindings(gl);
        bindings.configure(TERRAIN, true, true);
        gl.enabled[0] = gl.enabled[1] = gl.enabled[2] = false;
        bindings.configure(TERRAIN, true, true);
        gl.expect(true, true, true);
    }

    @Test void clearsOwnedStateOnExitThenLeavesUnrelatedRenderingAlone() {
        var gl = new Graphics();
        var bindings = new ClipProgramBindings(gl);
        bindings.configure(TERRAIN, true, true);
        bindings.configure(COPY, false, false);
        gl.expect(false, false, false);
        gl.enabled[2] = true; // A different renderer owns this outside IP/Sable.
        bindings.configure(COPY, false, false);
        gl.expect(false, false, true);
    }

    @Test void relinkedOrReusedProgramDoesNotReuseOldUniformLocations() {
        var gl = new Graphics();
        var bindings = new ClipProgramBindings(gl);
        gl.linked.put(42, TERRAIN);
        assertEquals(TERRAIN, bindings.locations(42));
        assertEquals(TERRAIN, bindings.locations(42));
        assertEquals(3, gl.queries);
        bindings.forget(42);
        gl.linked.put(42, COPY);
        bindings.configure(bindings.locations(42), true, true);
        gl.expect(false, false, false);
        assertEquals(6, gl.queries);
    }

    @Test void shipOnlyClippingDoesNotEnableThePortalPlane() {
        var gl = new Graphics();
        new ClipProgramBindings(gl).configure(TERRAIN, false, true);
        gl.expect(false, true, true);
    }
}
