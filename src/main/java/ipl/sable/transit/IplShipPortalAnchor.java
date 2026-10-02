package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas M6 (spec v3 §2.8, "portals on physics structures"): SHIP-ANCHORED PORTALS.
 *
 * <p>An anchored portal's origin end is glued to a sub-level at a ship-local pose.
 * Every server tick, immediately after the fused step publishes fresh physics poses,
 * the portal entity is re-posed from the ship: origin = shipPose(localPos),
 * orientation = shipRot ∘ localOrient. Each physical frame owns one attachment;
 * its flipped face shares that attachment. Both carrier poses are sampled before
 * updating any entities, then one driver rectifies the four faces from the pair.
 * An unattached end retains its last world pose. Server SavedData retains the
 * attachments across restarts; clients use their own interpolated carrier poses.
 *
 * <p>Physics sessions read the refreshed portal mapping. The existing kinematic
 * frame policy remains: no carrier traverses its own connection and traversal
 * impulses do not back-react on the anchor ship.
 */
public final class IplShipPortalAnchor {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-ship-portal");

    record Anchor(
        UUID shipId,
        ResourceKey<Level> portalDim,
        /**
         * PLOT-space portal origin — mapped through the ship's FULL pose transform
         * each tick, exactly like block positions. Never store a pose-position-
         * relative offset: {@code pose.position()} is center-of-mass-derived, and
         * any COM update (fluids, block changes) would silently re-base the offset
         * and drift the aperture off its frame.
         */
        Vector3d plotPos,
        DQuaternion localOrient,
        /** Last destination basis, retained if the other carrier detaches. */
        DQuaternion destLock
    ) {
        Anchor withDestLock(DQuaternion lock) {
            return new Anchor(shipId, portalDim, plotPos, localOrient, lock);
        }

        Anchor inDimension(ResourceKey<Level> dimension) {
            return new Anchor(shipId, dimension, plotPos, localOrient, destLock);
        }
    }

    /** Portal UUID → anchor. Server-thread only. */
    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

    /**
     * Anchors restored from the world SavedData whose ship hasn't resolved yet →
     * remaining grace ticks. Boot ordering: restore runs on the first server tick,
     * typically before portal chunks load AND before Sable's hosting container
     * finishes restoring its sub-levels — releasing on the first failed lookup would
     * silently drop every persisted anchor. While pending, the anchor neither drives
     * nor releases; on first resolve the carrier side effects and client sync apply.
     */
    private static final Map<UUID, Integer> RESTORE_PENDING = new HashMap<>();
    private static final int RESTORE_GRACE_TICKS = 1200; // 60s

    // ------------------------------------------------------------------
    // Persistence: a SavedData on the overworld — STATEFUL, not derived-at-save
    // from entity serialization. Vanilla owns WHEN to write (autosave, pause save,
    // stop save); we own only WHAT. This kills the whole save-ordering trap class
    // (the portal-NBT attempt lost every anchor because our stop cleanup cleared
    // the map before stopServer's save serialized the portal entities) and makes
    // restore independent of portal chunk-load timing.

    private static final String SAVED_DATA_NAME = "ipl_sable_ship_portal_anchors";
    /** A/B kill switch while the feature is young: -Dipl.sable.anchorPersistence=false */
    private static final boolean PERSISTENCE_ENABLED =
        !"false".equals(System.getProperty("ipl.sable.anchorPersistence"));
    /** Which server instance the on-disk anchors were restored for. */
    private static MinecraftServer restoredFor = null;

    public static final class AnchorSavedData extends net.minecraft.world.level.saveddata.SavedData {

        // Persistent snapshot, independent of shutdown clearing the runtime maps.
        private net.minecraft.nbt.ListTag snapshot = new net.minecraft.nbt.ListTag();

        void capture(Map<UUID, Anchor> anchors) {
            snapshot = encodeAnchors(anchors);
            setDirty();
        }

        public static AnchorSavedData get(ServerLevel overworld) {
            return overworld.getDataStorage().computeIfAbsent(
                new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                    AnchorSavedData::new,
                    (nbt, registries) -> {
                        AnchorSavedData data = new AnchorSavedData();
                        data.snapshot = nbt.getList("anchors", 10).copy();
                        return data;
                    },
                    null),
                SAVED_DATA_NAME);
        }

        @Override
        public net.minecraft.nbt.CompoundTag save(
            net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries
        ) {
            tag.put("anchors", snapshot.copy());
            return tag;
        }
    }

    static net.minecraft.nbt.ListTag encodeAnchors(Map<UUID, Anchor> anchors) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        anchors.forEach((id, a) -> {
            net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
            t.putUUID("portalId", id);
            t.putUUID("shipId", a.shipId());
            t.putString("dim", a.portalDim().location().toString());
            t.putDouble("plotX", a.plotPos().x);
            t.putDouble("plotY", a.plotPos().y);
            t.putDouble("plotZ", a.plotPos().z);
            putQuat(t, "lo", a.localOrient());
            putQuat(t, "dl", a.destLock());
            list.add(t);
        });
        return list;
    }

    static Map<UUID, Anchor> decodeAnchors(net.minecraft.nbt.ListTag list) {
        Map<UUID, Anchor> result = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                var t = list.getCompound(i);
                result.put(t.getUUID("portalId"), new Anchor(
                    t.getUUID("shipId"), ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(t.getString("dim"))),
                    new Vector3d(t.getDouble("plotX"), t.getDouble("plotY"), t.getDouble("plotZ")),
                    getQuat(t, "lo"), getQuat(t, "dl")));
            } catch (RuntimeException ex) {
                LOG.error("[IPL-SHIP-PORTAL] bad persisted anchor entry {}", i, ex);
            }
        }
        return result;
    }

    /** Applied lazily on the first tickAll of a server instance. */
    private static void restoreFromDisk(MinecraftServer server) {
        AnchorSavedData data = AnchorSavedData.get(server.overworld());
        decodeAnchors(data.snapshot).forEach((id, anchor) -> {
            ANCHORS.put(id, anchor);
            RESTORE_PENDING.put(id, RESTORE_GRACE_TICKS);
            LOG.info("[IPL-SHIP-PORTAL] restored anchor for portal {} (ship {}, pending resolve)",
                id, anchor.shipId());
        });
    }

    /** Mark the anchor set changed — vanilla persists it at the next save point. */
    private static void markDirty(MinecraftServer server) {
        if (!PERSISTENCE_ENABLED || server == null) return;
        AnchorSavedData.get(server.overworld()).capture(ANCHORS);
    }

    private static void putQuat(net.minecraft.nbt.CompoundTag tag, String prefix, DQuaternion q) {
        tag.putDouble(prefix + "X", q.getX());
        tag.putDouble(prefix + "Y", q.getY());
        tag.putDouble(prefix + "Z", q.getZ());
        tag.putDouble(prefix + "W", q.getW());
    }

    private static DQuaternion getQuat(net.minecraft.nbt.CompoundTag tag, String prefix) {
        return new DQuaternion(
            tag.getDouble(prefix + "X"), tag.getDouble(prefix + "Y"),
            tag.getDouble(prefix + "Z"), tag.getDouble(prefix + "W"));
    }

    private IplShipPortalAnchor() {}

    /** Server stopped: drop runtime state (persisted state lives in the world's SavedData). */
    public static void clearAll() {
        ANCHORS.clear();
        RESTORE_PENDING.clear();
        restoredFor = null;
        syncCounter = 0;
    }

    public static boolean isAnchored(UUID portalId) {
        return ANCHORS.containsKey(portalId);
    }

    /**
     * Identity of the fixed local attachment, independent of the carrier's moving world pose.
     * Server-thread read only: no world/entity lookup, loading, or anchor-state mutation.
     */
    public static String attachmentFingerprint(UUID primary) {
        Anchor anchor = primary == null ? null : ANCHORS.get(primary);
        if (anchor == null) return null;
        DQuaternion q = anchor.localOrient();
        return anchor.shipId() + ":" + anchor.portalDim().location() + ":"
            + Double.toHexString(anchor.plotPos().x) + ":" + Double.toHexString(anchor.plotPos().y) + ":"
            + Double.toHexString(anchor.plotPos().z) + ":" + Double.toHexString(q.x) + ":"
            + Double.toHexString(q.y) + ":" + Double.toHexString(q.z) + ":" + Double.toHexString(q.w);
    }

    /**
     * A ship must NEVER traverse (or straddle) its OWN anchored portal: the
     * carrier's forward motion constantly "crosses" the deck aperture, and a
     * self-transit would teleport the ship through a portal riding on itself.
     * The transit controller skips the (carrier, portal) pair entirely.
     */
    public static boolean isAnchorShip(UUID portalId, UUID shipId) {
        Anchor a = ANCHORS.get(portalId);
        return a != null && a.shipId().equals(shipId);
    }

    /**
     * Cluster-aware self-traversal test — USE THIS for session/transit gating.
     * Only the cluster PRIMARY lives in the anchor map, but bi-faced portals
     * (nether portals, datapack custom-gen) have a flipped twin on the SAME
     * plane with the opposite normal: gating by UUID alone lets the carrier
     * open a straddle session against its own portal's other face — an image
     * collider of itself through its own deck aperture (self-collision chaos).
     *
     * <p>Resolution goes through the extension's persisted cluster UUIDs, NOT the
     * lazy entity references: on world load the refs stay null until IP re-binds
     * the cluster, and in that window a ref-based guard waved the carrier's own
     * flipped face through — the rejoin wobble. The UUID fields are read straight
     * from the portal's NBT, so a loaded portal always has them. Entity refs are
     * kept as a fallback for runtime-created clusters mid-bind.
     */
    public static boolean isAnchorShip(Portal portal, UUID shipId) {
        if (isAnchorShip(portal.getUUID(), shipId)) return true;
        PortalExtension ext = PortalExtension.get(portal);
        return (ext.flippedPortalId != null && isAnchorShip(ext.flippedPortalId, shipId))
            || (ext.reversePortalId != null && isAnchorShip(ext.reversePortalId, shipId))
            || (ext.parallelPortalId != null && isAnchorShip(ext.parallelPortalId, shipId))
            || (ext.flippedPortal != null && isAnchorShip(ext.flippedPortal.getUUID(), shipId))
            || (ext.reversePortal != null && isAnchorShip(ext.reversePortal.getUUID(), shipId))
            || (ext.parallelPortal != null && isAnchorShip(ext.parallelPortal.getUUID(), shipId));
    }

    public static int count() {
        return ANCHORS.size();
    }

    /** UUIDs of all portals anchored to {@code shipId} (cluster primaries only). */
    public static java.util.List<UUID> anchoredPortalsOf(UUID shipId) {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Anchor> entry : ANCHORS.entrySet()) {
            if (entry.getValue().shipId().equals(shipId)) out.add(entry.getKey());
        }
        return out;
    }

    /**
     * Anchor {@code portal} to the sub-level whose bounds contain its origin (or the
     * nearest within 8 blocks). Returns a human-readable result message.
     */
    public static String anchor(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return "server side only";
        ServerSubLevel ship = findShipAt(level, portal.getOriginPos(), ANCHOR_MARGIN);
        if (ship == null) {
            return "no sub-level under the portal origin (stand the portal on the ship)";
        }
        return anchorToShip(portal, ship);
    }

    /**
     * Anchor {@code portal} to a KNOWN ship (generated-on-ship nether portals) —
     * same math as the command path, no proximity search. Requires the portal's
     * CURRENT world pose to correspond to the ship's CURRENT pose (true when the
     * portal was just posed from this ship, or the ship hasn't moved since).
     */
    public static String anchorToShip(Portal portal, ServerSubLevel ship) {
        Pose3dc pose = ship.logicalPose();
        Quaterniond shipRot = new Quaterniond(pose.orientation());
        Vec3 plotOrigin = pose.transformPositionInverse(portal.getOriginPos());

        DQuaternion shipD = new DQuaternion(shipRot.x, shipRot.y, shipRot.z, shipRot.w);
        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion localOrient = shipD.getConjugated().hamiltonProduct(o0);
        return register(portal, ship, plotOrigin, localOrient);
    }

    /**
     * Anchor with an EXACT plot-space origin — for portals whose world entity pose
     * is STALE relative to the ship. Assembly capture is the canonical case: the
     * capture waits for the rehome, and the assembled ship (a live physics body)
     * falls/drifts meanwhile while the not-yet-anchored portal entity stays at its
     * lit world position — deriving the anchor from that pose bakes the drift in
     * (observed: aperture offset ~3.5 blocks from its frame). The assembly
     * transform is a PURE TRANSLATION, so the caller knows the plot origin
     * exactly, and the portal's unchanged world orientation IS its plot-frame
     * orientation. The portal is snapped onto the ship's current pose immediately.
     */
    public static String anchorToShipAtPlot(Portal portal, ServerSubLevel ship, Vec3 plotOrigin) {
        // Plot frame == the portal's (stale) world frame up to translation, so the
        // plot-frame orientation is the portal's current orientation verbatim.
        DQuaternion localOrient = portal.getOrientationRotation();
        String result = register(portal, ship, plotOrigin, localOrient);
        if (portal.level() instanceof ServerLevel level && isAnchored(portal.getUUID())) {
            drivePortal(portal, samplePoses(level.getServer()), true);
        }
        return result;
    }

    private static String register(
        Portal portal, ServerSubLevel ship, Vec3 plotOrigin, DQuaternion localOrient
    ) {
        if (!(portal.level() instanceof ServerLevel level)) return "server side only";
        if (isCarrierPortalFace(portal)) return "this frame is already anchored";
        resolveCluster(portal);
        Vector3d localPos = new Vector3d(plotOrigin.x, plotOrigin.y, plotOrigin.z);
        DQuaternion o0 = portal.getOrientationRotation();
        DQuaternion rt0 = portal.getRotation() == null ? DQuaternion.identity : portal.getRotation();
        DQuaternion destLock = rt0.hamiltonProduct(o0);

        ANCHORS.put(portal.getUUID(), new Anchor(
            ship.getUniqueId(), level.dimension(), localPos, localOrient, destLock));
        markDirty(level.getServer());
        applyCarrierSideEffects(portal, ship, true);
        // A normal command anchor does not need to re-pose an already aligned portal,
        // so it previously waited for the manager's later scan to get a rim. Weld both
        // carrier faces now, including a completely stationary ship.
        followCarrierRims(portal);
        syncToClients(level.getServer(), portal.getUUID());
        LOG.info("[IPL-SHIP-PORTAL] anchored portal {} to ship {} at plot ({}, {}, {})",
            portal.getUUID(), ship.getUniqueId(),
            String.format("%.2f", localPos.x), String.format("%.2f", localPos.y),
            String.format("%.2f", localPos.z));
        return "anchored portal to ship " + ship.getUniqueId();
    }

    /** Detach just this physical endpoint, freezing it at the last carried pose. */
    public static String unanchor(Portal portal) {
        UUID id = endpointAnchor(portal);
        if (id == null || !(portal.level() instanceof ServerLevel level)) {
            return "portal was not anchored";
        }
        Portal primary = level.getEntity(id) instanceof Portal p ? p : null;
        if (primary != null) drivePortal(primary, samplePoses(level.getServer()), true);
        ANCHORS.remove(id);
        RESTORE_PENDING.remove(id);
        applyCarrierSideEffects(portal, null, false);
        markDirty(level.getServer());
        syncClearToClients(level.getServer(), id);
        // The survivor's last destination basis is now its fixed-end lock.
        for (UUID remaining : ANCHORS.keySet()) syncToClients(level.getServer(), remaining);
        return "unanchored";
    }

    /**
     * Anchored-portal side effects, applied to the whole same-level cluster
     * (the flipped twin shares the plane and has its own rim):
     *  - rim-vs-carrier exclusion: the containment rim must never push the hull
     *    that surrounds the aperture;
     *  - client lerp: IP's DefaultPortalAnimation eases synced state changes over
     *    10 ticks — half a second of aperture lag on a moving ship. Anchored
     *    portals get 1 tick (smooth at 20 TPS updates, no visible delay);
     *    restored to the IP default on unanchor.
     */
    private static void applyCarrierSideEffects(Portal portal, ServerSubLevel ship, boolean on) {
        int carrierId = on ? dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(ship) : -1;
        java.util.function.Consumer<Portal> perPortal = p -> {
            if (p.level() != portal.level()) return; // reverse/parallel sit at the dest
            if (on) {
                IplPortalRimManager.setCarrierExclusion(p.getUUID(), carrierId, true);
                p.animation.defaultAnimation.durationTicks = 1;
            } else {
                IplPortalRimManager.setCarrierExclusion(p.getUUID(), -1, false);
                p.animation.defaultAnimation.durationTicks = 10;
            }
        };
        perPortal.accept(portal);
        // Resolve the flipped face by persisted UUID, not just the lazy entity ref:
        // on a restored anchor the refs may still be unbound, and a missed flipped
        // face here leaves its rim WITHOUT the carrier exclusion — permanent
        // rim-vs-hull wobble after rejoin.
        PortalExtension ext = PortalExtension.get(portal);
        Portal flipped = ext.flippedPortal;
        if (flipped == null && ext.flippedPortalId != null
            && portal.level() instanceof ServerLevel sl
            && sl.getEntity(ext.flippedPortalId) instanceof Portal boundByUuid) {
            flipped = boundByUuid;
        }
        if (flipped != null) perPortal.accept(flipped);
    }

    /**
     * Is this portal — or any persisted cluster member — anchored to ANY ship?
     * Used by the rim manager to hold off rim creation until the anchor's carrier
     * exclusion has been applied (restored anchors resolve their ship ticks after
     * the portal loads; a rim spawned in that window grinds against its own hull).
     */
    public static boolean isAnchoredCluster(Portal portal) {
        if (ANCHORS.containsKey(portal.getUUID())) return true;
        PortalExtension ext = PortalExtension.get(portal);
        return (ext.flippedPortalId != null && ANCHORS.containsKey(ext.flippedPortalId))
            || (ext.reversePortalId != null && ANCHORS.containsKey(ext.reversePortalId))
            || (ext.parallelPortalId != null && ANCHORS.containsKey(ext.parallelPortalId))
            || (ext.flippedPortal != null && ANCHORS.containsKey(ext.flippedPortal.getUUID()))
            || (ext.reversePortal != null && ANCHORS.containsKey(ext.reversePortal.getUUID()))
            || (ext.parallelPortal != null && ANCHORS.containsKey(ext.parallelPortal.getUUID()));
    }

    /**
     * True for the anchored origin face and its same-level flipped face. These are the
     * carrier's physical aperture faces; the other endpoint can have its own attachment.
     */
    public static boolean isCarrierPortalFace(Portal portal) {
        return endpointAnchor(portal) != null;
    }

    private static UUID endpointAnchor(Portal portal) {
        PortalExtension ext = PortalExtension.get(portal);
        return ShipPortalMotion.sameEnd(portal.getUUID(),
            memberId(ext.flippedPortalId, ext.flippedPortal), ANCHORS::containsKey);
    }

    private static UUID memberId(UUID saved, Portal resolved) {
        return saved != null ? saved : resolved == null ? null : resolved.getUUID();
    }

    private static ShipPortalMotion.Partner partner(Portal portal) {
        PortalExtension ext = PortalExtension.get(portal);
        return ShipPortalMotion.otherEnd(memberId(ext.reversePortalId, ext.reversePortal),
            memberId(ext.parallelPortalId, ext.parallelPortal), ANCHORS::containsKey);
    }

    /**
     * Drive all anchored portals from their ships' fresh physics poses. Called at the
     * end of the fused step (IplFusedStep), when this tick's poses are final.
     */
    private static int syncCounter = 0;

    private record TransitFace(Portal portal, UUID flipped, UUID reverse, UUID parallel,
                               ResourceKey<Level> destination, ShipPortalMotion.Mapping mapping) {}

    /**
     * A portal entity belongs to the carrier's parent level, while its frame blocks
     * remain in the hosting plot. Only the attachment's two faces change levels.
     * Collect by persisted UUID, never by proximity to the portal being crossed.
     */
    public static CarrierTransit prepareCarrierTransit(ServerSubLevel ship, ServerLevel oldParent,
                                                       ServerLevel newParent, Pose3dc mappedPose) {
        MinecraftServer server = oldParent.getServer();
        Map<UUID, Anchor> carried = new java.util.LinkedHashMap<>();
        ANCHORS.forEach((id, anchor) -> {
            if (anchor.shipId().equals(ship.getUniqueId())) carried.put(id, anchor);
        });
        Map<UUID, ShipPortalMotion.Pose> poses = samplePoses(server);
        carried.forEach((id, anchor) -> poses.put(id, new ShipPortalMotion.Pose(
            mappedPose.transformPosition(new Vec3(anchor.plotPos().x, anchor.plotPos().y, anchor.plotPos().z)),
            DQuaternion.fromMcQuaternion(new Quaterniond(mappedPose.orientation())).hamiltonProduct(anchor.localOrient()))));
        Map<UUID, TransitFace> faces = new java.util.LinkedHashMap<>();
        Map<UUID, Portal> moving = new java.util.LinkedHashMap<>();
        try {
            for (var entry : carried.entrySet()) {
                if (entry.getValue().portalDim() != oldParent.dimension()) {
                    throw new IllegalStateException("Carrier and attached portal have different parent dimensions: " + entry.getKey());
                }
                Portal primary = requiredPortal(oldParent, entry.getKey());
                resolveCluster(primary);
                PortalExtension ext = PortalExtension.get(primary);
                Portal flipped = requiredPortal(oldParent, memberId(ext.flippedPortalId, ext.flippedPortal));
                ServerLevel far = server.getLevel(primary.getDestDim());
                Portal reverse = requiredPortal(far, memberId(ext.reversePortalId, ext.reversePortal));
                Portal parallel = requiredPortal(far, memberId(ext.parallelPortalId, ext.parallelPortal));
                ShipPortalMotion.Partner other = partner(primary);
                ShipPortalMotion.Mapping mapping = ShipPortalMotion.resolve(entry.getKey(), other, poses,
                    primary.getDestPos(), entry.getValue().destLock());
                if (mapping == null) throw new IllegalStateException("Other portal carrier has not loaded: " + entry.getKey());
                ResourceKey<Level> destination = other != null && carried.containsKey(other.id())
                    ? newParent.dimension() : primary.getDestDim();
                rememberTransitFace(faces, primary, destination, mapping);
                rememberTransitFace(faces, flipped, destination, mapping.flipped());
                rememberTransitFace(faces, reverse, newParent.dimension(), mapping.returning(true));
                rememberTransitFace(faces, parallel, newParent.dimension(), mapping.returning(false));
                moving.put(primary.getUUID(), primary);
                if (flipped != null) moving.put(flipped.getUUID(), flipped);
            }
            // Same-dimension traversal changes the pose but needs no entity recreation.
            var batch = PortalTransferBatch.prepare(oldParent == newParent ? java.util.List.<Portal>of()
                    : java.util.List.copyOf(moving.values()), original -> {
                if (newParent.getEntity(original.getUUID()) != null) {
                    throw new IllegalStateException("Destination already contains portal " + original.getUUID());
                }
                Entity copy = original.getType().create(newParent);
                if (!(copy instanceof Portal replacement)) throw new IllegalStateException("Cannot recreate " + original);
                replacement.restoreFrom(original);
                replacement.setId(original.getId());
                if (!replacement.getUUID().equals(original.getUUID())) throw new IllegalStateException("Portal identity changed");
                applyTransitFace(replacement, faces.get(original.getUUID()));
                return replacement;
            }, newParent::addFreshEntity, portal -> {
                if (newParent.getEntity(portal.getUUID()) == portal) portal.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            });
            if (batch == null) {
                TRANSIT_FAILURES.invoke(() -> LOG.warn(
                    "[IPL-SHIP-PORTAL] destination rejected a portal face; carrier {} has not changed parent", ship.getUniqueId()));
                return null;
            }
            return new CarrierTransit(ship, newParent, carried, faces, batch);
        } catch (RuntimeException ex) {
            TRANSIT_FAILURES.invoke(() -> LOG.warn("[IPL-SHIP-PORTAL] cannot prepare attached portals for carrier {}", ship.getUniqueId(), ex));
            return null;
        }
    }

    private static final qouteall.q_misc_util.my_util.LimitedLogger TRANSIT_FAILURES =
        new qouteall.q_misc_util.my_util.LimitedLogger(20);

    private static Portal requiredPortal(ServerLevel level, UUID id) {
        if (id == null || id.equals(net.minecraft.Util.NIL_UUID)) return null;
        if (level != null && level.getEntity(id) instanceof Portal portal && !portal.isRemoved()) return portal;
        throw new IllegalStateException("Attached portal face is not loaded: " + id);
    }

    private static void rememberTransitFace(Map<UUID, TransitFace> faces, Portal portal,
                                           ResourceKey<Level> destination, ShipPortalMotion.Mapping mapping) {
        if (portal == null) return;
        PortalExtension ext = PortalExtension.get(portal);
        faces.put(portal.getUUID(), new TransitFace(portal, memberId(ext.flippedPortalId, ext.flippedPortal),
            memberId(ext.reversePortalId, ext.reversePortal), memberId(ext.parallelPortalId, ext.parallelPortal),
            destination, mapping));
    }

    private static void applyTransitFace(Portal portal, TransitFace face) {
        portal.setOriginPos(face.mapping().origin().position());
        portal.setOrientationRotation(face.mapping().origin().orientation());
        portal.setDestination(face.mapping().destination());
        portal.setRotation(face.mapping().rotation());
        portal.setDestDim(face.destination());
    }

    /** Prepared replacements are removed if the frame handoff aborts. */
    public static final class CarrierTransit implements AutoCloseable {
        private final ServerSubLevel ship;
        private final ServerLevel destination;
        private final Map<UUID, Anchor> carried;
        private final Map<UUID, TransitFace> faces;
        private final PortalTransferBatch<Portal> batch;

        private CarrierTransit(ServerSubLevel ship, ServerLevel destination, Map<UUID, Anchor> carried,
                               Map<UUID, TransitFace> faces, PortalTransferBatch<Portal> batch) {
            this.ship = ship;
            this.destination = destination;
            this.carried = carried;
            this.faces = faces;
            this.batch = batch;
        }

        public void commit() {
            Map<UUID, Portal> current = new HashMap<>();
            faces.forEach((id, face) -> current.put(id, face.portal()));
            batch.replacements().forEach(pair -> current.put(pair.destination().getUUID(), pair.destination()));
            batch.commit(portal -> portal.remove(Entity.RemovalReason.CHANGED_DIMENSION));
            faces.forEach((id, face) -> applyTransitFace(current.get(id), face));
            // All four references must be rebound together. A lazy reference to a
            // removed source entity can otherwise break pairing on the next tick.
            faces.forEach((id, face) -> {
                Portal portal = current.get(id);
                PortalExtension ext = PortalExtension.get(portal);
                ext.flippedPortalId = face.flipped();
                ext.reversePortalId = face.reverse();
                ext.parallelPortalId = face.parallel();
                ext.flippedPortal = current.get(face.flipped());
                ext.reversePortal = current.get(face.reverse());
                ext.parallelPortal = current.get(face.parallel());
                portal.reloadAndSyncToClientNextTick();
            });
            carried.forEach((id, anchor) -> ANCHORS.put(id, anchor.inDimension(destination.dimension())));
            Map<UUID, ShipPortalMotion.Pose> poses = samplePoses(destination.getServer());
            carried.forEach((id, anchor) -> {
                Portal portal = current.get(id);
                applyCarrierSideEffects(portal, ship, true);
                drivePortal(portal, poses, true);
            });
            if (!carried.isEmpty()) {
                markDirty(destination.getServer());
                for (UUID id : ANCHORS.keySet()) syncToClients(destination.getServer(), id);
                LOG.info("[IPL-SHIP-PORTAL] carried {} portal attachment(s) with ship {} into {}",
                    carried.size(), ship.getUniqueId(), destination.dimension().location());
            }
        }

        @Override public void close() { batch.close(); }
    }

    public static void tickAll(MinecraftServer server) {
        if (PERSISTENCE_ENABLED && restoredFor != server) {
            restoredFor = server;
            restoreFromDisk(server);
        }
        if (ANCHORS.isEmpty()) return;
        // Late-joiner bootstrap: the anchor payload is tiny; rebroadcast on a slow
        // cadence instead of tracking per-player join state.
        if ((syncCounter++ % 100) == 0) {
            for (UUID portalId : ANCHORS.keySet().toArray(new UUID[0])) {
                syncToClients(server, portalId);
            }
        }

        boolean removedAttachment = false;
        Iterator<Map.Entry<UUID, Anchor>> it = ANCHORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Anchor> entry = it.next();
            Anchor a = entry.getValue();

            ServerLevel level = server.getLevel(a.portalDim());
            if (level == null) continue;
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null) continue; // unloaded — keep the anchor, skip this tick
            if (!(entity instanceof Portal portal) || portal.isRemoved()) {
                LOG.info("[IPL-SHIP-PORTAL] portal {} gone — anchor dropped", entry.getKey());
                syncClearToClients(server, entry.getKey());
                RESTORE_PENDING.remove(entry.getKey());
                it.remove();
                removedAttachment = true;
                markDirty(server);
                continue;
            }

            ServerSubLevel ship = findShip(server, a.shipId());
            if (ship == null || ship.isRemoved()) {
                Integer grace = RESTORE_PENDING.get(entry.getKey());
                if (grace != null && grace > 0) {
                    RESTORE_PENDING.put(entry.getKey(), grace - 1);
                    continue; // restored anchor, ship still loading — wait, don't drive
                }
                RESTORE_PENDING.remove(entry.getKey());
                LOG.info("[IPL-SHIP-PORTAL] ship {} gone — portal {} released",
                    a.shipId(), entry.getKey());
                applyCarrierSideEffects(portal, null, false);
                syncClearToClients(server, entry.getKey());
                it.remove();
                removedAttachment = true;
                markDirty(server);
                continue;
            }
            if (RESTORE_PENDING.remove(entry.getKey()) != null) {
                // Restored anchor's ship just resolved: finish what anchorToShip
                // would have done live (deferred — the physics scene and container
                // aren't available during entity NBT read at boot).
                applyCarrierSideEffects(portal, ship, true);
                syncToClients(server, entry.getKey());
                LOG.info("[IPL-SHIP-PORTAL] restored anchor {} resolved to ship {}",
                    entry.getKey(), a.shipId());
            }

        }

        if (removedAttachment) {
            for (UUID id : ANCHORS.keySet()) syncToClients(server, id);
        }

        Map<UUID, ShipPortalMotion.Pose> poses = samplePoses(server);
        java.util.Set<UUID> driven = new java.util.HashSet<>();
        for (UUID id : ANCHORS.keySet().toArray(new UUID[0])) {
            if (driven.contains(id)) continue;
            Anchor anchor = ANCHORS.get(id);
            ServerLevel level = server.getLevel(anchor.portalDim());
            if (level == null || !(level.getEntity(id) instanceof Portal portal)) continue;
            ShipPortalMotion.Partner other = partner(portal);
            if (drivePortal(portal, poses, false)) {
                driven.add(id);
                if (other != null) driven.add(other.id());
            }
        }
    }

    private static Map<UUID, ShipPortalMotion.Pose> samplePoses(MinecraftServer server) {
        Map<UUID, ShipPortalMotion.Pose> poses = new HashMap<>();
        ANCHORS.forEach((id, a) -> {
            ServerSubLevel ship = findShip(server, a.shipId());
            if (ship == null || ship.isRemoved()) return;
            Pose3dc pose = ship.logicalPose();
            Vec3 position = pose.transformPosition(new Vec3(a.plotPos().x, a.plotPos().y, a.plotPos().z));
            DQuaternion rotation = DQuaternion.fromMcQuaternion(new Quaterniond(pose.orientation()));
            poses.put(id, new ShipPortalMotion.Pose(position, rotation.hamiltonProduct(a.localOrient())));
        });
        return poses;
    }

    /** One coherent mapping drives all four faces, independent of map iteration order. */
    private static boolean drivePortal(
        Portal portal, Map<UUID, ShipPortalMotion.Pose> poses, boolean force
    ) {
        Anchor a = ANCHORS.get(portal.getUUID());
        ShipPortalMotion.Pose origin = poses.get(portal.getUUID());
        if (a == null || origin == null) return false;
        ShipPortalMotion.Partner other = partner(portal);
        // An attached but unresolved carrier is not a fixed endpoint. Wait rather
        // than snapping back to its old lock during join/chunk loading.
        ShipPortalMotion.Mapping mapping = ShipPortalMotion.resolve(portal.getUUID(), other, poses,
            portal.getDestPos(), a.destLock());
        if (mapping == null) return false;
        resolveCluster(portal);
        PortalExtension ext = PortalExtension.get(portal);
        // A counterpart can reload with an older entity pose while both carriers
        // are stationary. It still needs rectification before we mark the pair done.
        if (!force && !needsUpdate(portal, mapping)
            && !needsUpdate(ext.flippedPortal, mapping.flipped())
            && !needsUpdate(ext.reversePortal, mapping.returning(true))
            && !needsUpdate(ext.parallelPortal, mapping.returning(false))) return true;
        portal.setOriginPos(origin.position());
        portal.setOrientationRotation(origin.orientation());
        portal.setDestination(mapping.destination());
        portal.setRotation(mapping.rotation());
        portal.reloadAndSyncToClientNextTick();
        PortalExtension.get(portal).rectifyClusterPortals(portal, true);
        followCarrierRims(portal);
        if (other != null) {
            Anchor b = ANCHORS.get(other.id());
            ANCHORS.put(portal.getUUID(), a.withDestLock(mapping.destinationBasis()));
            DQuaternion returnBasis = mapping.rotation().getConjugated()
                .hamiltonProduct(poses.get(other.id()).orientation());
            ANCHORS.put(other.id(), b.withDestLock(returnBasis));
            ServerLevel far = portal.getServer().getLevel(b.portalDim());
            if (far != null && far.getEntity(other.id()) instanceof Portal partner) {
                followCarrierRims(partner);
            }
            markDirty(portal.getServer());
        }
        return true;
    }

    private static boolean needsUpdate(Portal portal, ShipPortalMotion.Mapping mapping) {
        return portal != null && ShipPortalMotion.changed(mapping,
            new ShipPortalMotion.Pose(portal.getOriginPos(), portal.getOrientationRotation()),
            portal.getDestPos(), portal.getRotation() == null ? DQuaternion.identity : portal.getRotation());
    }

    /** Bind loaded members by persisted UUID too; lazy IP references may lag assembly/rejoin. */
    private static void resolveCluster(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        PortalExtension ext = PortalExtension.get(portal);
        ServerLevel far = level.getServer().getLevel(portal.getDestDim());
        ext.flippedPortal = resolveMember(level, ext.flippedPortalId, ext.flippedPortal);
        ext.reversePortal = resolveMember(far, ext.reversePortalId, ext.reversePortal);
        ext.parallelPortal = resolveMember(far, ext.parallelPortalId, ext.parallelPortal);
    }

    private static Portal resolveMember(ServerLevel level, UUID id, Portal fallback) {
        if (id != null && level != null && level.getEntity(id) instanceof Portal portal
            && !portal.isRemoved()) return portal;
        return fallback != null && !fallback.isRemoved() ? fallback : null;
    }

    /** Create/update both same-level carrier-face rims from their rectified portal poses. */
    private static void followCarrierRims(Portal portal) {
        if (!(portal.level() instanceof ServerLevel level)) return;
        IplPortalRimManager.followDrivenPortal(level, portal);
        PortalExtension ext = PortalExtension.get(portal);
        Portal flipped = ext.flippedPortal;
        if (flipped == null && ext.flippedPortalId != null
            && level.getEntity(ext.flippedPortalId) instanceof Portal byId) {
            flipped = byId;
        }
        if (flipped != null && flipped.level() == portal.level()) {
            IplPortalRimManager.followDrivenPortal(level, flipped);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Acceptance margin (blocks) between the portal origin and the ship's BOUNDS —
     * measured to the box surface, not the center, so large hulls qualify. Generous
     * by default: a deck-mounted portal floats a rim's width above the hull, and the
     * origin of a wand-made portal can sit a few blocks off the frame.
     */
    private static final double ANCHOR_MARGIN =
        Double.parseDouble(System.getProperty("ipl.sable.shipPortal.anchorMargin", "8.0"));

    /**
     * Atlas M6 WELD: mirror the anchor to clients — the client drives the portal's
     * pose per FRAME from the ship's interpolated render pose, pixel-locking the
     * aperture to the hull (see ipl.sable.client.IplClientShipPortalAnchor).
     */
    private static void syncToClients(MinecraftServer server, UUID portalId) {
        Anchor a = ANCHORS.get(portalId);
        if (a == null || server == null) return;
        ServerLevel level = server.getLevel(a.portalDim());
        String flippedId = "";
        String reverseId = "";
        String parallelId = "";
        if (level != null && level.getEntity(portalId) instanceof Portal portal) {
            PortalExtension ext = PortalExtension.get(portal);
            UUID flipped = memberId(ext.flippedPortalId, ext.flippedPortal);
            UUID reverse = memberId(ext.reversePortalId, ext.reversePortal);
            UUID parallel = memberId(ext.parallelPortalId, ext.parallelPortal);
            if (flipped != null) flippedId = flipped.toString();
            if (reverse != null) reverseId = reverse.toString();
            if (parallel != null) parallelId = parallel.toString();
        }
        String localPos = a.plotPos().x + "," + a.plotPos().y + "," + a.plotPos().z;
        String localOrient = a.localOrient().getX() + "," + a.localOrient().getY() + ","
            + a.localOrient().getZ() + "," + a.localOrient().getW();
        String destLock = a.destLock().getX() + "," + a.destLock().getY() + ","
            + a.destLock().getZ() + "," + a.destLock().getW();
        for (net.minecraft.server.level.ServerPlayer player
                : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplClientShipPortalAnchor.RemoteCallables.set",
                portalId.toString(), flippedId, reverseId, parallelId,
                a.shipId().toString(), localPos, localOrient, destLock);
        }
    }

    private static void syncClearToClients(MinecraftServer server, UUID portalId) {
        if (server == null) return;
        for (net.minecraft.server.level.ServerPlayer player
                : server.getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplClientShipPortalAnchor.RemoteCallables.clear",
                portalId.toString());
        }
    }

    /**
     * Find the ship nearest {@code pos} whose EFFECTIVE PARENT is {@code level}.
     * Hosted ships' gameplay state lives in the HOSTING dimension's container (the
     * dim-agnostic architecture), so scanning only the portal's own level finds
     * nothing — the same disease as Sable's nearby-sub-level commands. Enumerate
     * every container and filter by parent instead.
     */
    private static ServerSubLevel findShipAt(ServerLevel level, Vec3 pos, double margin) {
        ServerSubLevel best = null;
        double bestDist = margin * margin;
        for (ServerLevel any : level.getServer().getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(any);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.isRemoved()) continue;
                ServerLevel parent = ipl.sable.dim.IplDimAgnostic.isHosted(sub)
                    ? ipl.sable.dim.IplDimAgnostic.getServerParentLevel(sub)
                    : (sub.getLevel() instanceof ServerLevel sl ? sl : null);
                if (parent != level) continue;
                var bb = sub.boundingBox();
                // Distance from the origin to the closest point of the ship's AABB
                // (zero when inside): a portal hovering just above a large deck is
                // near the SURFACE while being far from the center.
                double dx = Math.max(0.0, Math.max(bb.minX() - pos.x, pos.x - bb.maxX()));
                double dy = Math.max(0.0, Math.max(bb.minY() - pos.y, pos.y - bb.maxY()));
                double dz = Math.max(0.0, Math.max(bb.minZ() - pos.z, pos.z - bb.maxZ()));
                double d = dx * dx + dy * dy + dz * dz;
                if (d == 0.0) return sub;
                if (d < bestDist) {
                    bestDist = d;
                    best = sub;
                }
            }
        }
        return best;
    }

    private static ServerSubLevel findShip(MinecraftServer server, UUID shipId) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub.getUniqueId().equals(shipId)) return sub;
            }
        }
        return null;
    }
}
