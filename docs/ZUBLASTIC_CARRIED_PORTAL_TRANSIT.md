# Active portals carried across dimensions

## Report and cause

The owner moved a small active Nether portal's Sable frame through a larger
active portal, also on a Sable frame. The large portal kept working and the
small frame reached the Nether, but its active aperture did not survive.
This report does not by itself distinguish an entity left behind from deletion.

`SableRehomeOps.executeHostedTransit` already remaps a hosted body's pose and
velocities, teleports its riders and changes its parent dimension. Its plot
blocks stay in the hosting level. However, the attached portal entities lived
in the old parent level and `IplShipPortalAnchor.Anchor.portalDim` remained the
old dimension. The anchor driver could therefore apply the new carrier pose
to a portal still registered in the old world. This missing handoff is separate
from the earlier two-independently-moving-endpoints repair.

## Handoff

The fork now prepares the carrier's attached endpoints before changing its
parent. Membership comes from the attachment map and persisted cluster UUIDs,
not a nearby-entity search: crossing a large portal must not transfer that
unrelated connection along with the small frame.

- Sample both endpoint poses, substituting the carrier's mapped destination pose.
- Resolve the carried faces and their linked far-end faces before proceeding.
  A referenced but unavailable face defers the handoff; it is not silently lost.
- Recreate only the carried origin and flipped faces in the new parent level,
  retaining their type, UUID, entity ID and serialized portal/frame data.
- Publish all replacements before retiring any original. Rejection or an
  exception during preparation removes the staged copies and leaves the
  originals in place. Abandoning the prepared handoff also removes the copies.
- After the body changes parent, retire old faces with `CHANGED_DIMENSION`,
  update mappings and dimension keys, rebind all cluster references, and persist
  each carried attachment's new home. The original far-end frame remains where
  it is and points back to the moved frame. Nether-to-Nether links are valid.
- Resolve client portal entities in the carrier's current parent dimension;
  skip incomplete handoffs instead of driving an old-world entity with a
  new-world pose. Update both faces and return mappings coherently.

Same-dimension crossings reuse the existing entities. Multiple attachments on
one carrier are staged together; both independently attached endpoints retain
their own carrier and plot-space attachment. Ordinary rider teleportation still
excludes portal entities. This is not an attempt to make every physics, rider,
third-party event listener and network operation a crash-atomic transaction.

## .14 live failure and .15 repair

The .14 owner test crashed during the attempted small-through-large crossing.
The owner additionally reports that the small frame pushed the whole large
frame even though the obsidian frames never touched. Do not describe this as a
successful small-frame transfer merely because a server-side handoff completed.

The crash stack reaches `ClientPortalAnimationManagement.update`'s completed
default-animation path, then `Portal.setPortalState` rejects the stale destination
dimension. The in-progress path checked dimensions; the completed path did not.
.15 samples an animation only after both endpoint states match the live origin
and destination, before interpolation or completion. Cancellation preserves the
new authoritative state and does not emit an animation-finished event. Normal
same-dimension motion and new animations after a handoff remain enabled.

The crossing-volume cache also included every non-air block, including invisible
portal placeholders. These have an empty collision shape (also respected by
installed Sable 2.0.5), but the detector treated them as full occupied cubes.
Consequently lighting a hollow frame filled its entire opening in crossing
calculations: a small portal could admit that phantom interior to a session.
.15 excludes only these placeholders, preserving other carried blocks' behavior.
The log shows both carriers starting sessions before one backs out and the other
rehomes. This is evidence of the invalid-admission mechanism, not a native contact
trace proving every force in the owner's pushing report. Live retesting remains
necessary; no global collision disabling, frame freezing or Sable fork is used.

Regression tests reproduce the stale completed-animation failures and the filled
aperture volume before their respective fixes. Tests also exercise in-progress
dimension changes, invalid endpoint pairs, ordinary interpolation and animation
after handoff, all placeholder axes, and retaining normal carried blocks.

## Validation and limits

Candidate **.15** passes the Java 21 build and **111 tests, zero skipped**,
including the seven existing DH GPU checks and nine new animation/volume cases.
Its JAR SHA-256 is
`23cafb3607b1800748dc9644a8c7a138d83745fd2302d33ad5346847e618fe86`.
Against .14, only `IplPortalVolumeCache` and `ClientPortalAnimationManagement`
class families differ. Native physics and all other class bytes are unchanged.
Local evidence, including the captured crash and failing-then-passing regressions,
is in `M:/PortalCarriedRepair-20260928/`. These tests do not simulate native
contact forces, entity tracking or rendering during the live crossing.

Previous candidate `0.5.1-zublastic.14+ip-6.0.7` built with Java 21. All **102 tests passed,
zero skipped**, including the existing seven DH GPU cases. Eight new cases
exercise production batch staging/rollback, identity retention, preserving
unrelated and far-end entities, and attachment persistence after a dimension
handoff. Existing dual-end geometry tests continue to pass. The registry fixture
does not run Minecraft entity tracking, Sable physics or packet ordering.

JAR SHA-256: `58a355939c3d07e66c46cf18c55585c74c67e14e4477c20904ef2a1514ec9c87`.
Physics native SHA-256 remains
`19105f177b03ea37c308c9d9fc58de55867bbd6d3cbd850c249d72b24c47fcc6`.
Only the anchor/server-handoff/client-anchor class families changed against
the .13 artifact. Existing render compatibility and outline classes are
byte-identical; no Sable, Iris or DH fork or mod-lineup change is involved.

**.14 failed live; .15 acceptance is pending.** In Portal Lab, use a fresh working small portal
and move its assembled frame fully through the large active portal. Check that
both remain active, the small aperture follows its frame, its original far end
points back to the new location, and player travel works both ways. Move it back
and repeat with the far-end frame also assembled; save/reload and check again.
Visual continuity during straddling, dedicated-server clients and unloaded
counterparts are not established by the unit tests. Already-lost apertures are
not reconstructed automatically by this change.
