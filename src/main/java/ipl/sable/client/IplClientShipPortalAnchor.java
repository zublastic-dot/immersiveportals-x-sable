package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.transit.ShipPortalMotion;
import qouteall.imm_ptl.core.portal.PortalExtension;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas M6 WELD: client-side per-FRAME driving of ship-anchored portals.
 *
 * <p>The server's per-tick portal updates are correct, but the client renders the
 * SHIP through Sable's snapshot interpolator (its own delay/timeline) while the
 * portal follows IP's entity sync — two clocks, so the aperture swims against the
 * hull at speed. The weld derives the portal's client pose FROM the ship's
 * interpolated {@code renderPose()} every frame (pre-render), so both live on one
 * clock and the aperture is pixel-locked to the deck.
 *
 * <p>Server packets still arrive (and keep teleportation state honest); whatever
 * they write is overwritten here before rendering. The flipped twin (bi-faced
 * nether portals) is driven from the same math with the mirrored width axis —
 * exactly what {@code rectifyClusterPortals} does server-side.
 */
public final class IplClientShipPortalAnchor {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-ship-portal-client");

    private record ClientAnchor(
        UUID flippedId,
        UUID reverseId,
        UUID parallelId,
        UUID shipId,
        /** PLOT-space origin — mapped through the ship's FULL render pose per frame,
         * exactly like block vertices (COM-invariant; see the server anchor). */
        Vector3d plotPos,
        DQuaternion localOrient,
        DQuaternion destLock
    ) {
        ClientAnchor withDestLock(DQuaternion lock) {
            return new ClientAnchor(flippedId, reverseId, parallelId, shipId, plotPos, localOrient, lock);
        }
    }

    /** Portal UUID → anchor. Client thread only. */
    private static final Map<UUID, ClientAnchor> ANCHORS = new HashMap<>();
    /** Last known flipped face for detached impostors only; never used by the live portal driver. */
    private static final Map<UUID, UUID> IMPOSTOR_FLIPPED_ALIASES = new HashMap<>();

    private static boolean registered = false;

    private IplClientShipPortalAnchor() {}

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        qouteall.imm_ptl.core.render.impostor.PortalImpostorMetadata.setClientAnchorLookup(
            IplClientShipPortalAnchor::getImpostorAnchorId);
        // World teardown: drop all client anchor state. Without this, stale anchors
        // survive disconnect and the per-frame driver runs against a torn-down
        // ClientWorldLoader — getClientWorlds() Validate.isTrue crashed the render
        // thread mid-disconnect (emergencySaveAndCrash = unclean server shutdown).
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            de.nick1st.imm_ptl.events.ClientCleanupEvent.class, e -> {
                ANCHORS.clear();
                IMPOSTOR_FLIPPED_ALIASES.clear();
                PORTAL_CACHE.clear();
            });
        // ORDERING IS THE WELD: IP's ClientPortalAnimationManagement.update() runs at
        // the head of GameRenderer.render, and an anchored portal always has a fresh
        // 1-tick default animation chasing the latest SERVER-tick pose — any earlier
        // hook (RenderFrameEvent.Pre) gets overwritten and the aperture renders AHEAD
        // of the snapshot-interpolated hull, 20Hz-stepped. IP emits this signal at the
        // END of update(), right before teleportation management and rendering — the
        // one point where our write is final for the frame (and teleport math sees
        // the welded pose too).
        qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement
            .clientAnimationUpdateSignal.connect(IplClientShipPortalAnchor::driveAll);
    }

    public enum Status { STATIC, UNRESOLVED, RESOLVED }

    /** Immutable render pose, including a local-attachment fingerprint stable during rigid motion. */
    public record ImpostorPose(Status status, UUID carrierId, Vec3 origin, Vec3 axisW, Vec3 axisH,
                               String attachmentFingerprint) {}

    /** Primary anchor UUID for this aperture or its flipped face. No entity lookup or world creation. */
    public static UUID getImpostorAnchorId(UUID portalId) {
        if (portalId == null) return null;
        if (ANCHORS.containsKey(portalId)) return portalId;
        for (var entry : ANCHORS.entrySet()) {
            if (portalId.equals(entry.getValue().flippedId())
                || portalId.equals(IMPOSTOR_FLIPPED_ALIASES.get(entry.getKey()))) return entry.getKey();
        }
        return null;
    }

    /**
     * Resolve an aperture after its Portal entity is untracked, using the same interpolated carrier
     * pose as live geometry. This is read-only and never uses PORTAL_CACHE or loads another world.
     */
    public static ImpostorPose resolveImpostorPose(UUID portalId, ResourceKey<Level> expectedParent) {
        UUID primary = getImpostorAnchorId(portalId);
        if (primary == null) return new ImpostorPose(Status.STATIC, null, null, null, null, "");
        ClientAnchor anchor = ANCHORS.get(primary);
        boolean flipped = !primary.equals(portalId);
        String fingerprint = attachmentFingerprint(primary, anchor, flipped);
        ImpostorPose unresolved = new ImpostorPose(Status.UNRESOLVED, anchor.shipId(), null, null, null, fingerprint);
        if (!ClientWorldLoader.getIsInitialized() || net.minecraft.client.Minecraft.getInstance().level == null) return unresolved;
        ClientSubLevel ship = findShip(anchor.shipId());
        if (ship == null || ship.isRemoved()) return unresolved;
        Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(ship);
        if (parent == null || !parent.dimension().equals(expectedParent)) return unresolved;
        Pose3dc pose = ship.renderPose();
        Vec3 origin = pose.transformPosition(new Vec3(anchor.plotPos().x, anchor.plotPos().y, anchor.plotPos().z));
        DQuaternion rotation = DQuaternion.fromMcQuaternion(new Quaterniond(pose.orientation()))
            .hamiltonProduct(anchor.localOrient());
        Vec3 axisW = rotation.rotate(new Vec3(flipped ? -1 : 1, 0, 0));
        Vec3 axisH = rotation.rotate(new Vec3(0, 1, 0));
        return new ImpostorPose(Status.RESOLVED, anchor.shipId(), origin, axisW, axisH, fingerprint);
    }

    private static String attachmentFingerprint(UUID primary, ClientAnchor anchor, boolean flipped) {
        DQuaternion q = anchor.localOrient();
        return primary + ":" + anchor.shipId() + ":" + flipped + ":"
            + Double.toHexString(anchor.plotPos().x) + ":" + Double.toHexString(anchor.plotPos().y) + ":"
            + Double.toHexString(anchor.plotPos().z) + ":" + Double.toHexString(q.x) + ":"
            + Double.toHexString(q.y) + ":" + Double.toHexString(q.z) + ":" + Double.toHexString(q.w);
    }

    private static void driveAll() {
        if (ANCHORS.isEmpty()) return;
        // Torn-down or not-yet-initialized client world state: getClientWorlds()
        // hard-validates initialization, and the game can render frames on the
        // disconnect path after teardown — never drive there.
        if (!ClientWorldLoader.getIsInitialized()
            || net.minecraft.client.Minecraft.getInstance().level == null) {
            return;
        }
        // Snapshot BOTH carrier render poses before changing any of the four faces.
        Map<UUID, ShipPortalMotion.Pose> poses = new HashMap<>();
        Map<UUID, Portal> portals = new HashMap<>();
        ANCHORS.forEach((id, a) -> {
            ClientSubLevel ship = findShip(a.shipId());
            if (ship == null || ship.isRemoved()) return;
            // Entity and carrier handoff packets may arrive in different frames.
            // Never pose an old-level portal using the new-level carrier transform.
            Level parent = ipl.sable.dim.IplDimAgnostic.getParentLevel(ship);
            Portal portal = parent == null ? null : findPortal(id, parent.dimension());
            if (portal == null) return;
            portals.put(id, portal);
            Pose3dc pose = ship.renderPose();
            Vec3 position = pose.transformPosition(new Vec3(a.plotPos().x, a.plotPos().y, a.plotPos().z));
            DQuaternion rotation = DQuaternion.fromMcQuaternion(new Quaterniond(pose.orientation()));
            poses.put(id, new ShipPortalMotion.Pose(position, rotation.hamiltonProduct(a.localOrient())));
        });
        java.util.Set<UUID> driven = new java.util.HashSet<>();
        for (UUID id : ANCHORS.keySet().toArray(new UUID[0])) {
            if (driven.contains(id)) continue;
            ClientAnchor a = ANCHORS.get(id);
            ShipPortalMotion.Pose origin = poses.get(id);
            Portal portal = portals.get(id);
            if (origin == null || portal == null) continue;
            PortalExtension ext = PortalExtension.get(portal);
            UUID flipped = a.flippedId() != null ? a.flippedId() : ext.flippedPortalId;
            UUID reverse = a.reverseId() != null ? a.reverseId() : ext.reversePortalId;
            UUID parallel = a.parallelId() != null ? a.parallelId() : ext.parallelPortalId;
            ShipPortalMotion.Partner other = ShipPortalMotion.otherEnd(reverse, parallel, ANCHORS::containsKey);
            ShipPortalMotion.Mapping mapping = ShipPortalMotion.resolve(id, other, poses, portal.getDestPos(), a.destLock());
            if (mapping == null) continue;
            ResourceKey<Level> destination = other == null ? portal.getDestDim()
                : portals.get(other.id()).getOriginDim();

            portal.setOriginPos(origin.position());
            portal.setOrientationRotation(origin.orientation());
            portal.setDestination(mapping.destination());
            portal.setRotation(mapping.rotation());
            portal.setDestDim(destination);
            Portal twin = flipped == null ? null : findPortal(flipped, portal.getOriginDim());
            if (twin != null) {
                twin.setOriginPos(origin.position());
                twin.setOrientation(portal.getAxisW().scale(-1), portal.getAxisH());
                twin.setDestination(mapping.destination());
                twin.setRotation(mapping.rotation());
                twin.setDestDim(destination);
            }
            applyFarFace(reverse, mapping.returning(true), destination, portal.getOriginDim());
            applyFarFace(parallel, mapping.returning(false), destination, portal.getOriginDim());
            driven.add(id);
            if (other != null) {
                driven.add(other.id());
                // Keep a current fixed-end fallback if a subsequent clear removes
                // either carrier. The remaining endpoint must not jump to ignition.
                ANCHORS.put(id, a.withDestLock(mapping.destinationBasis()));
                ClientAnchor b = ANCHORS.get(other.id());
                ANCHORS.put(other.id(), b.withDestLock(mapping.rotation().getConjugated()
                    .hamiltonProduct(poses.get(other.id()).orientation())));
            }
        }
    }

    private static void applyFarFace(UUID id, ShipPortalMotion.Mapping mapping,
                                     ResourceKey<Level> originDim, ResourceKey<Level> destinationDim) {
        Portal far = id == null ? null : findPortal(id, originDim);
        if (far == null) return;
        far.setOriginPos(mapping.origin().position());
        far.setDestination(mapping.destination());
        far.setOrientationRotation(mapping.origin().orientation());
        far.setRotation(mapping.rotation());
        far.setDestDim(destinationDim);
    }

    /** Resolved portal entities (client Level.getEntities() is protected — scan once, cache). */
    private static final Map<UUID, Portal> PORTAL_CACHE = new HashMap<>();

    private static Portal findPortal(UUID id, ResourceKey<Level> dimension) {
        Portal cached = PORTAL_CACHE.get(id);
        if (cached != null && !cached.isRemoved() && cached.getOriginDim() == dimension) return cached;
        PORTAL_CACHE.remove(id);
        for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            if (level.dimension() != dimension) continue;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof Portal portal && !portal.isRemoved()
                    && portal.getUUID().equals(id)) {
                    PORTAL_CACHE.put(id, portal);
                    return portal;
                }
            }
        }
        return null;
    }

    private static ClientSubLevel findShip(UUID shipId) {
        for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            SubLevel sub = container.getSubLevel(shipId);
            if (sub instanceof ClientSubLevel client) return client;
        }
        return null;
    }

    public static final class RemoteCallables {

        /** Anchor set/update: doubles are ','-joined; cluster UUIDs may be empty. */
        public static void set(
            String portalUuid, String flippedUuid, String reverseUuid, String parallelUuid,
            String shipUuid, String localPos, String localOrient, String destLock
        ) {
            try {
                ensureRegistered();
                double[] p = parse(localPos, 3);
                double[] o = parse(localOrient, 4);
                double[] d = parse(destLock, 4);
                PORTAL_CACHE.remove(UUID.fromString(portalUuid));
                if (parseUuid(flippedUuid) != null) PORTAL_CACHE.remove(parseUuid(flippedUuid));
                if (parseUuid(reverseUuid) != null) PORTAL_CACHE.remove(parseUuid(reverseUuid));
                if (parseUuid(parallelUuid) != null) PORTAL_CACHE.remove(parseUuid(parallelUuid));
                UUID primary = UUID.fromString(portalUuid);
                // The server's periodic anchor sync lacks cluster IDs while the source entity is
                // unloaded. Keep a detached-render alias without changing the live driver's state.
                // Authoritative impostor leases separately invalidate deleted/relinked faces.
                UUID flipped = parseUuid(flippedUuid);
                if (flipped != null) IMPOSTOR_FLIPPED_ALIASES.put(primary, flipped);
                ANCHORS.put(primary, new ClientAnchor(
                    flipped,
                    parseUuid(reverseUuid),
                    parseUuid(parallelUuid),
                    UUID.fromString(shipUuid),
                    new Vector3d(p[0], p[1], p[2]),
                    new DQuaternion(o[0], o[1], o[2], o[3]),
                    new DQuaternion(d[0], d[1], d[2], d[3])));
            } catch (Throwable t) {
                LOG.error("[IPL-SHIP-PORTAL] bad anchor sync", t);
            }
        }

        private static UUID parseUuid(String s) {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        }

        public static void clear(String portalUuid) {
            try {
                UUID id = UUID.fromString(portalUuid);
                ANCHORS.remove(id);
                IMPOSTOR_FLIPPED_ALIASES.remove(id);
            } catch (Throwable ignored) {
            }
        }

        private static double[] parse(String csv, int n) {
            String[] parts = csv.split(",");
            double[] out = new double[n];
            for (int i = 0; i < n; i++) out[i] = Double.parseDouble(parts[i]);
            return out;
        }
    }
}
