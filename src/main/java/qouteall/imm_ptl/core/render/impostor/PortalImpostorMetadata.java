package qouteall.imm_ptl.core.render.impostor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ipl.sable.transit.IplShipPortalAnchor;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Immutable aperture/link description. It owns no entity, world, chunk or renderer. */
public record PortalImpostorMetadata(
    UUID portalId, String sourceDimension, String destinationDimension,
    Vec3 origin, Vec3 axisW, Vec3 axisH, double width, double height,
    Vec3 destination, Vec3 destinationAxisW, Vec3 destinationAxisH, double scale,
    UUID sourceAnchor, UUID destinationAnchor,
    UUID flippedPortal, UUID reversePortal, UUID parallelPortal
) {
    public static final int MAX_JSON_LENGTH = 4096;
    private static Function<UUID, UUID> clientAnchorLookup = id -> null;

    public PortalImpostorMetadata {
        Objects.requireNonNull(portalId, "portalId");
        validateDimension(sourceDimension);
        validateDimension(destinationDimension);
        validatePosition(origin);
        validatePosition(destination);
        validateBasis(axisW, axisH);
        validateBasis(destinationAxisW, destinationAxisH);
        if (!Double.isFinite(width) || width <= 0 || width > 4096
            || !Double.isFinite(height) || height <= 0 || height > 4096
            || !Double.isFinite(scale) || scale < 1.0e-6 || scale > 1.0e6) {
            throw new IllegalArgumentException("Invalid impostor extent/scale");
        }
    }

    /** Registered from the client anchor code; common code never loads a client class. */
    public static void setClientAnchorLookup(Function<UUID, UUID> lookup) {
        clientAnchorLookup = Objects.requireNonNull(lookup);
    }

    public static PortalImpostorMetadata fromPortal(Portal portal) {
        var extension = PortalExtension.get(portal);
        var other = portal.getOtherSideState();
        Function<UUID, UUID> lookup = portal.level().isClientSide()
            ? clientAnchorLookup : id -> id != null && IplShipPortalAnchor.isAnchored(id) ? id : null;
        return new PortalImpostorMetadata(
            portal.getUUID(), portal.getOriginDim().location().toString(), portal.getDestDim().location().toString(),
            portal.getOriginPos(), portal.getAxisW(), portal.getAxisH(), portal.getWidth(), portal.getHeight(),
            portal.getDestPos(), other.getAxisW(), other.getAxisH(), portal.getScale(),
            firstAnchor(lookup, portal.getUUID(), extension.flippedPortalId),
            firstAnchor(lookup, extension.reversePortalId, extension.parallelPortalId),
            extension.flippedPortalId, extension.reversePortalId, extension.parallelPortalId
        );
    }

    private static UUID firstAnchor(Function<UUID, UUID> lookup, UUID first, UUID second) {
        UUID found = first == null ? null : lookup.apply(first);
        return found != null || second == null ? found : lookup.apply(second);
    }

    /** Rigid carrier motion is allowed; re-linking, resizing and static endpoint motion are not. */
    public boolean linkMatches(PortalImpostorMetadata other) {
        return other != null && portalId.equals(other.portalId)
            && sourceDimension.equals(other.sourceDimension) && destinationDimension.equals(other.destinationDimension)
            && Objects.equals(sourceAnchor, other.sourceAnchor) && Objects.equals(destinationAnchor, other.destinationAnchor)
            && Objects.equals(flippedPortal, other.flippedPortal) && Objects.equals(reversePortal, other.reversePortal)
            && Objects.equals(parallelPortal, other.parallelPortal)
            && close(width, other.width) && close(height, other.height) && close(scale, other.scale)
            && (sourceAnchor != null || samePose(origin, axisW, axisH, other.origin, other.axisW, other.axisH))
            && (destinationAnchor != null || samePose(destination, destinationAxisW, destinationAxisH,
                other.destination, other.destinationAxisW, other.destinationAxisH));
    }

    public boolean references(String dimension, UUID id) {
        return sourceDimension.equals(dimension) && (portalId.equals(id) || Objects.equals(flippedPortal, id))
            || destinationDimension.equals(dimension)
                && (Objects.equals(reversePortal, id) || Objects.equals(parallelPortal, id));
    }

    public Vec3 normal() { return axisW.cross(axisH); }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("portalId", portalId.toString());
        json.addProperty("sourceDimension", sourceDimension);
        json.addProperty("destinationDimension", destinationDimension);
        putVector(json, "origin", origin);
        putVector(json, "axisW", axisW);
        putVector(json, "axisH", axisH);
        json.addProperty("width", width);
        json.addProperty("height", height);
        putVector(json, "destination", destination);
        putVector(json, "destinationAxisW", destinationAxisW);
        putVector(json, "destinationAxisH", destinationAxisH);
        json.addProperty("scale", scale);
        putUuid(json, "sourceAnchor", sourceAnchor);
        putUuid(json, "destinationAnchor", destinationAnchor);
        putUuid(json, "flippedPortal", flippedPortal);
        putUuid(json, "reversePortal", reversePortal);
        putUuid(json, "parallelPortal", parallelPortal);
        String result = json.toString();
        if (result.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("Oversized impostor metadata");
        return result;
    }

    public static PortalImpostorMetadata fromJson(String value) {
        if (value == null || value.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("Oversized impostor metadata");
        try {
            JsonObject j = JsonParser.parseString(value).getAsJsonObject();
            return new PortalImpostorMetadata(
                UUID.fromString(j.get("portalId").getAsString()), j.get("sourceDimension").getAsString(),
                j.get("destinationDimension").getAsString(), vector(j, "origin"), vector(j, "axisW"), vector(j, "axisH"),
                j.get("width").getAsDouble(), j.get("height").getAsDouble(), vector(j, "destination"),
                vector(j, "destinationAxisW"), vector(j, "destinationAxisH"), j.get("scale").getAsDouble(),
                uuid(j, "sourceAnchor"), uuid(j, "destinationAnchor"), uuid(j, "flippedPortal"),
                uuid(j, "reversePortal"), uuid(j, "parallelPortal")
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid impostor metadata", exception);
        }
    }

    private static void validateDimension(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
            throw new IllegalArgumentException("Invalid dimension id");
        }
    }

    private static void validatePosition(Vec3 value) {
        if (value == null || !Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)
            || Math.abs(value.x) > 32_000_000 || Math.abs(value.y) > 32_000_000 || Math.abs(value.z) > 32_000_000) {
            throw new IllegalArgumentException("Invalid impostor position");
        }
    }

    private static void validateBasis(Vec3 w, Vec3 h) {
        validatePosition(w);
        validatePosition(h);
        if (Math.abs(w.lengthSqr() - 1) > 1.0e-5 || Math.abs(h.lengthSqr() - 1) > 1.0e-5 || Math.abs(w.dot(h)) > 1.0e-5) {
            throw new IllegalArgumentException("Invalid impostor basis");
        }
    }

    private static boolean samePose(Vec3 p, Vec3 w, Vec3 h, Vec3 p2, Vec3 w2, Vec3 h2) {
        return p.distanceToSqr(p2) < 1.0e-6 && w.distanceToSqr(w2) < 1.0e-10 && h.distanceToSqr(h2) < 1.0e-10;
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) <= 1.0e-6 * Math.max(1, Math.max(Math.abs(a), Math.abs(b))); }
    private static void putUuid(JsonObject j, String name, UUID id) { if (id != null) j.addProperty(name, id.toString()); }
    private static UUID uuid(JsonObject j, String name) { return !j.has(name) ? null : UUID.fromString(j.get(name).getAsString()); }
    private static void putVector(JsonObject j, String name, Vec3 v) {
        JsonArray a = new JsonArray(); a.add(v.x); a.add(v.y); a.add(v.z); j.add(name, a);
    }
    private static Vec3 vector(JsonObject j, String name) {
        JsonArray a = j.getAsJsonArray(name);
        if (a.size() != 3) throw new IllegalArgumentException("Invalid vector length");
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
}
