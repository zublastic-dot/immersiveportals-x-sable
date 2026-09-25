package ipl.sable.render;

import java.util.HashMap;
import java.util.Map;

/** Render-thread state for the three clip distances owned by IP/Sable. */
final class ClipProgramBindings {
    interface Driver {
        int uniformLocation(int program, String name);
        void clipDistance(int slot, boolean enabled);
    }

    record Locations(int portal, int subLevel, int secondSubLevel) {}

    private final Driver driver;
    private final Map<Integer, Locations> locations = new HashMap<>();
    private boolean managedPreviousBind;

    ClipProgramBindings(Driver driver) {
        this.driver = driver;
    }

    Locations locations(int program) {
        return locations.computeIfAbsent(program, p -> new Locations(
            driver.uniformLocation(p, "iportal_ClippingEquation"),
            driver.uniformLocation(p, "ipl_subLevelClipEquation"),
            driver.uniformLocation(p, "ipl_subLevelClipEquation[1]")
        ));
    }

    void forget(int program) {
        locations.remove(program);
    }

    void configure(Locations shader, boolean portalActive, boolean subLevelActive) {
        boolean managed = portalActive || subLevelActive;
        if (managed || managedPreviousBind) {
            // Copy shaders do not write our clip distances. Enabling them anyway
            // makes clipping of their output undefined. Post-effects may also
            // change the hardware state independently of FrontClipping's flag.
            driver.clipDistance(0, portalActive && shader.portal() >= 0);
            driver.clipDistance(1, subLevelActive && shader.subLevel() >= 0);
            driver.clipDistance(2, subLevelActive && shader.secondSubLevel() >= 0);
        }
        managedPreviousBind = managed;
    }
}
