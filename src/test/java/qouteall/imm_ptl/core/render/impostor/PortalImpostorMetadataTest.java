package qouteall.imm_ptl.core.render.impostor;

import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PortalImpostorMetadataTest {
    static final UUID PORTAL = new UUID(0, 1);
    static final UUID FLIPPED = new UUID(0, 2);
    static final UUID REVERSE = new UUID(0, 3);
    static final UUID PARALLEL = new UUID(0, 4);
    static final Vec3 W = new Vec3(1, 0, 0), H = new Vec3(0, 1, 0);

    static PortalImpostorMetadata fixture(UUID sourceAnchor, UUID destinationAnchor) {
        return new PortalImpostorMetadata(PORTAL, "minecraft:overworld", "minecraft:the_nether",
            new Vec3(110, 180, 220), W, H, 12, 16, new Vec3(14, 50, 28), W.scale(-1), H, 1,
            sourceAnchor, destinationAnchor, FLIPPED, REVERSE, PARALLEL);
    }

    static PortalImpostorMetadata at(PortalImpostorMetadata m, Vec3 origin, Vec3 w, Vec3 h, Vec3 destination,
                                     Vec3 destinationW, Vec3 destinationH) {
        return new PortalImpostorMetadata(m.portalId(), m.sourceDimension(), m.destinationDimension(),
            origin, w, h, m.width(), m.height(), destination, destinationW, destinationH, m.scale(),
            m.sourceAnchor(), m.destinationAnchor(), m.flippedPortal(), m.reversePortal(), m.parallelPortal());
    }

    @Test void metadataRoundTripsWithoutAnEntityOrWorld() {
        var staticPortal = fixture(null, null);
        assertEquals(staticPortal, PortalImpostorMetadata.fromJson(staticPortal.toJson()));
        var carried = fixture(PORTAL, REVERSE);
        assertEquals(carried, PortalImpostorMetadata.fromJson(carried.toJson()));
        assertTrue(carried.toJson().length() < PortalImpostorMetadata.MAX_JSON_LENGTH);
        assertEquals(new Vec3(0, 0, 1), carried.normal());
    }

    @Test void malformedNetworkMetadataCannotProduceNonFiniteOrNonOrthonormalGeometry() {
        var j = JsonParser.parseString(fixture(null, null).toJson()).getAsJsonObject();
        j.addProperty("width", "NaN");
        assertThrows(IllegalArgumentException.class, () -> PortalImpostorMetadata.fromJson(j.toString()));
        j.addProperty("width", 12);
        j.getAsJsonArray("axisH").set(0, new com.google.gson.JsonPrimitive(1));
        assertThrows(IllegalArgumentException.class, () -> PortalImpostorMetadata.fromJson(j.toString()));
        j.getAsJsonArray("axisH").set(0, new com.google.gson.JsonPrimitive(0));
        j.addProperty("sourceDimension", "../../invalid");
        assertThrows(IllegalArgumentException.class, () -> PortalImpostorMetadata.fromJson(j.toString()));
        assertThrows(IllegalArgumentException.class, () -> PortalImpostorMetadata.fromJson(" ".repeat(4097)));
    }

    @Test void invalidCoordinatesExtentsAndDegenerateBasisAreRejected() {
        var m = fixture(null, null);
        assertThrows(IllegalArgumentException.class, () -> at(m, new Vec3(Double.NaN, 0, 0), W, H,
            m.destination(), m.destinationAxisW(), H));
        assertThrows(IllegalArgumentException.class, () -> at(m, m.origin(), Vec3.ZERO, H,
            m.destination(), m.destinationAxisW(), H));
        for (double width : new double[]{0, -1, Double.POSITIVE_INFINITY, 4097}) {
            var json = JsonParser.parseString(m.toJson()).getAsJsonObject();
            json.addProperty("width", Double.isFinite(width) ? Double.toString(width) : "Infinity");
            assertThrows(IllegalArgumentException.class, () -> PortalImpostorMetadata.fromJson(json.toString()));
        }
    }

    @Test void staticEndpointMovementInvalidatesTheCapturedPicture() {
        var m = fixture(null, null);
        assertFalse(m.linkMatches(at(m, m.origin().add(0.01, 0, 0), W, H, m.destination(), m.destinationAxisW(), H)));
        assertFalse(m.linkMatches(at(m, m.origin(), W, H, m.destination().add(0, 0, 1), m.destinationAxisW(), H)));
        assertFalse(m.linkMatches(at(m, m.origin(), W.scale(-1), H, m.destination(), m.destinationAxisW(), H)));
    }

    @Test void onlyAnchoredEndpointsMayMoveWithoutInvalidatingTheLink() {
        var carried = fixture(PORTAL, REVERSE);
        var moved = at(carried, new Vec3(500, 210, 40), new Vec3(0, 0, 1), H,
            new Vec3(600, 70, 80), new Vec3(0, 0, -1), H);
        assertTrue(carried.linkMatches(moved));
        assertTrue(moved.linkMatches(carried));
        var oneEnd = fixture(PORTAL, null);
        assertTrue(oneEnd.linkMatches(at(oneEnd, moved.origin(), moved.axisW(), H,
            oneEnd.destination(), oneEnd.destinationAxisW(), H)));
        assertFalse(oneEnd.linkMatches(at(oneEnd, moved.origin(), moved.axisW(), H,
            moved.destination(), moved.destinationAxisW(), H)));
    }

    @Test void anchoredResizeRelinkOrReanchorInvalidatesDespitePermittedRigidMotion() {
        var m = fixture(PORTAL, REVERSE);
        for (String field : new String[]{"width", "height", "scale"}) {
            var j = JsonParser.parseString(m.toJson()).getAsJsonObject();
            j.addProperty(field, 2);
            assertFalse(m.linkMatches(PortalImpostorMetadata.fromJson(j.toString())));
        }
        for (String field : new String[]{"sourceAnchor", "destinationAnchor", "flippedPortal", "reversePortal", "parallelPortal"}) {
            var j = JsonParser.parseString(m.toJson()).getAsJsonObject();
            j.addProperty(field, new UUID(0, 99).toString());
            assertFalse(m.linkMatches(PortalImpostorMetadata.fromJson(j.toString())));
        }
        var j = JsonParser.parseString(m.toJson()).getAsJsonObject();
        j.addProperty("destinationDimension", "minecraft:the_end");
        assertFalse(m.linkMatches(PortalImpostorMetadata.fromJson(j.toString())));
    }

    @Test void deletionMatchesClusterMembersOnlyInTheirActualDimension() {
        var m = fixture(null, null);
        assertTrue(m.references("minecraft:overworld", PORTAL));
        assertTrue(m.references("minecraft:overworld", FLIPPED));
        assertTrue(m.references("minecraft:the_nether", REVERSE));
        assertTrue(m.references("minecraft:the_nether", PARALLEL));
        assertFalse(m.references("minecraft:the_nether", PORTAL));
        assertFalse(m.references("minecraft:overworld", REVERSE));
        assertFalse(m.references("minecraft:overworld", new UUID(0, 99)));
    }
}
