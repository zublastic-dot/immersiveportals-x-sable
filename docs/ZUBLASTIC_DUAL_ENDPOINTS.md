# Independently moving portal endpoints, 2026-09-27

The owner reproduced a first-assembled-frame-wins defect in Portal Lab: either
the Overworld or Nether endpoint followed its carrier when assembled first,
while the second did not. Both tested Overworld ignition orders failed alike.
Source and installed .6 bytecode had a deliberate whole-cluster anchor guard;
both movement drivers assumed a fixed destination. The inherited design notes
also list ship-to-ship double-anchored pairs as unfinished work.

## Candidate .7

Each physical endpoint now owns one attachment. Its flipped face shares that
attachment, while the reverse/parallel endpoint may own a second attachment.
Assembly and the manual anchor path both enforce this distinction. The map is
still keyed by portal UUID; existing single-end SavedData entries are readable.

Server ticks and client render frames sample both carrier poses before changing
entities. One driver per connection updates all four faces. For reverse faces,
the rotation is `O_destination * flip_about_local_up * inverse(O_origin)`;
for parallel faces the flip is omitted. Full plot-position transforms retain the
existing center-of-mass invariance. Client samples use Sable's render poses after
IP's animation update; server samples use logical poses after the fused step.

The static skip includes destination translation/rotation and loaded counterpart
poses. An attached carrier which has not resolved does not become a fixed endpoint
temporarily. Cluster lookup and sync use persisted UUIDs as well as lazy references.
Detachment removes only one endpoint, retains the other endpoint's latest
destination basis, and synchronizes the survivor. Both same-frame faces retain
the existing carrier callbacks and self-transit exclusion. The rim manager in
this source is currently a no-op; this change does not restore native rim physics.

SavedData now owns a copied snapshot instead of serializing the static runtime
map during a later save. This prevents cleanup before a delayed save from erasing
attachments. Capturing changes refreshes that snapshot; clearing runtime state
does not. Field names and quaternion convention remain compatible with .6.

## Validation boundary

All 56 local JUnit tests and the Java 21 native-preserving build pass. Fifteen new
tests exercise production geometry/selection and persistence code: both endpoint
selection orders, reverse/parallel faces, simultaneous noncommuting rotations and
translations, reciprocal mappings, destination-only movement, single-end motion,
detached-end locks, unresolved carrier deferral, old/new NBT round trips, and
save-after-cleanup. These are deterministic code tests, not an in-game assembly,
network, disassembly or restart acceptance test. Earlier camera/depth tests pass.

Live acceptance is pending. Test a fresh linked pair in both assembly orders;
translate and tilt each frame separately, then both; inspect both faces and
traverse both directions; disassemble either frame; save/rejoin and repeat.
Use matching .7 client/server code for a dedicated-server trial. The owner lab's
integrated server shares the client JAR. No Iris, Real Camera or Sable fork is added.

An endpoint skipped by .6 has no saved attachment. Upgrading cannot reconstruct
an attachment that was never recorded. Use fresh frames for the controlled trial,
or explicitly disassemble/reassemble the affected frame. Automatic world repair
is outside this change. Scaling, carrier transit through unrelated portals and
high-speed contact velocity/back-reaction remain outside this acceptance scope.
