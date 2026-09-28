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

## Validation and limits

Candidate `0.5.1-zublastic.14+ip-6.0.7` builds with Java 21. All **102 tests pass,
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

**Live acceptance is pending.** In Portal Lab, use a fresh working small portal
and move its assembled frame fully through the large active portal. Check that
both remain active, the small aperture follows its frame, its original far end
points back to the new location, and player travel works both ways. Move it back
and repeat with the far-end frame also assembled; save/reload and check again.
Visual continuity during straddling, dedicated-server clients and unloaded
counterparts are not established by the unit tests. Already-lost apertures are
not reconstructed automatically by this change.
