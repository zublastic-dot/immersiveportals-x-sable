package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.UUID;
import java.util.Map;
import java.util.function.Predicate;

/** Shared server-tick/client-frame geometry for independently carried portal ends. */
public final class ShipPortalMotion {
    private ShipPortalMotion() {}

    // Reverse faces negate local width and normal, retaining local up.
    public static final DQuaternion FLIP = new DQuaternion(0, 1, 0, 0);

    public record Pose(Vec3 position, DQuaternion orientation) {}
    public record Partner(UUID id, boolean reverse) {}
    public record Mapping(Pose origin, Vec3 destination, DQuaternion rotation) {
        public DQuaternion destinationBasis() {
            return rotation.hamiltonProduct(origin.orientation());
        }

        public Mapping flipped() {
            return new Mapping(new Pose(origin.position(), origin.orientation().hamiltonProduct(FLIP)), destination, rotation);
        }

        public Mapping returning(boolean reverse) {
            DQuaternion orientation = reverse ? destinationBasis().hamiltonProduct(FLIP) : destinationBasis();
            return new Mapping(new Pose(destination, orientation), origin.position(), rotation.getConjugated());
        }
    }

    /** Deduplicate only the two faces of one physical frame. */
    public static UUID sameEnd(UUID id, UUID flipped, Predicate<UUID> anchored) {
        if (anchored.test(id)) return id;
        return flipped != null && anchored.test(flipped) ? flipped : null;
    }

    public static Partner otherEnd(UUID reverse, UUID parallel, Predicate<UUID> anchored) {
        if (reverse != null && anchored.test(reverse)) return new Partner(reverse, true);
        if (parallel != null && anchored.test(parallel)) return new Partner(parallel, false);
        return null;
    }

    /** Both poses are sampled before any portal entities are changed. */
    public static Mapping between(Pose origin, Pose destination, boolean reverse) {
        DQuaternion exit = reverse
            ? destination.orientation().hamiltonProduct(FLIP) : destination.orientation();
        return toFixed(origin, destination.position(), exit);
    }

    public static Mapping toFixed(Pose origin, Vec3 destination, DQuaternion destinationBasis) {
        return new Mapping(origin, destination,
            destinationBasis.hamiltonProduct(origin.orientation().getConjugated()));
    }

    /** A known attachment whose carrier has not loaded is never treated as a fixed end. */
    public static Mapping resolve(UUID id, Partner other, Map<UUID, Pose> poses, Vec3 destination, DQuaternion lock) {
        Pose origin = poses.get(id);
        if (origin == null || (other != null && !poses.containsKey(other.id()))) return null;
        return other == null ? toFixed(origin, destination, lock)
            : between(origin, poses.get(other.id()), other.reverse());
    }

    public static boolean sameRotation(DQuaternion a, DQuaternion b) {
        double dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        return Math.abs(dot) >= 1.0 - 1.0e-10;
    }

    /** A stationary origin must still update when its destination moves. */
    public static boolean changed(Mapping desired, Pose origin, Vec3 destination, DQuaternion rotation) {
        return origin.position().distanceToSqr(desired.origin().position()) > 1.0e-10
            || !sameRotation(origin.orientation(), desired.origin().orientation())
            || destination.distanceToSqr(desired.destination()) > 1.0e-10
            || !sameRotation(rotation, desired.rotation());
    }
}
