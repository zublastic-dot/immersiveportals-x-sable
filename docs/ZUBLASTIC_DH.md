# Distant Horizons 3.3.2 portal views

Status on 2026-09-27: **IMPLEMENTED_NOT_LIVE_ACCEPTED**. Candidate .8 is a
client integration inside IP/Sable. DH's JAR is neither edited nor bundled.
It builds on .7; this does not newly accept or merge the earlier PR stack.

## Reproduction and implementation evidence

The owner reports Nether vanilla geometry visible from the Overworld but no
Nether LODs until crossing, with the reciprocal failure looking back. The supplied
F3 screenshot has shaders disabled, DH 3.3.2, Sodium 0.8.13 and an integrated
server. Installed DH SHA-256:
`cf63717e037bc2576933d63d11246b5f2f317f4cd5b6d4ea1ea077ad027e4a36`.
The exact Modrinth artifact is `Ez3cx7Yd` (`3.3.2-1.21.1`).

Inspection of that artifact establishes these independent restrictions:

- `AbstractImmersivePortalsAccessor.BeforeRenderEvent` cancels every render while
  `PortalRendering.isRendering()` is true.
- Both `DhClientServerLevel.clientTick` and `DhClientLevel.clientTick` return
  unless their level is the actual player's current level. DH deliberately caches
  actual player state across IP's temporary world switches.
- `DhClientServerLevel.getClientLevelWrapper` returns the global current wrapper,
  which cannot identify a remote level while asynchronous LOD work uses it.
- Lightmap ownership ordinarily prefers the last `ClientApi.RENDER_STATE`, but
  IP updates a destination lightmap before the destination terrain-layer hook.

The first two explain the reported boundary. They are code evidence, not proof
that they are the only remaining incompatibilities in this mod stack.

## Scoped changes

Optional client mixins activate only for DH 3.3.2. Other versions and absent DH
retain their existing behavior. The normal render hook supplies the destination
wrapper and transformed camera; an immutable view is published to that DH level.
DH's existing timer can then tick recently visible destinations under a
thread-local wrapper/position override. Actual player caches are not rewritten.
Views expire after two seconds and are released when the DH level closes.

Only DH's blanket IP cancellation is bypassed, within our prepared render pass;
other event listeners retain their ability to cancel. Remote integrated LOD work
gets its own level wrapper. Lightmaps use the currently switched client world.
Nested render state, GL bindings, clip enable, clear values and the parent's
state are restored when the pass ends, including exception unwinding.

DH shaders do not write IP's clip distance. That GL feature is disabled for DH's
pass and an oblique projection clips LOD geometry against the moving portal's
destination plane. Both forward and reverse depth conventions are handled.
DH's temporal anti-aliasing is excluded from portal passes to avoid sharing its
main-view history across dimensions. DH's existing portal fade exclusion stays.

## Validation and limits

All **67 JUnit tests** pass, including 11 new tests for exact DH method contracts,
forward/reverse depth clipping, rotated planes, preserved screen coordinates,
degenerate inputs, nested/thread-local cleanup, expiry and version gates. The
Java 21 native-preserving build succeeds. This is not a live rendering result.

Candidate .8 SHA-256:
`d0f22f8046ca0ac13ae7ea1c8ba0122e22845e812422748d6f7040dbe1ddec7d`.
All **987 prior classes other than the mixin-selection plugin** are byte-identical
to .7, including camera, packed-depth and dual-frame code. Native SHA-256 remains
`19105f177b03ea37c308c9d9fc58de55867bbd6d3cbd850c249d72b24c47fcc6`.

DH retains one LOD quadtree per level. The actual player view takes precedence
in that dimension; this does not implement multiple independent distant centers
for same-dimension portals. Multiple far-apart portal destinations in one remote
dimension, full shader-stack behavior, terrain never previously generated, and
remote-server DH generation permissions require separate live validation.
Unit tests do not execute a GPU, mixin transformation in Minecraft, or DH workers.

## Portal Lab trial

Keep all other JARs and settings fixed. Start without shaders as in the reported
control. Inspect both directions while still on the source side, allow DH to
load its LOD buffers, then cross and compare. Check moving/tilted frames,
foreground occlusion, ordinary terrain after turning away, and save/rejoin.
Repeat with Complementary and Euphoria separately. Logs distinguish
`preparing portal view` from `portal LOD render submitted ... buffers`; neither
message alone proves a correct image. Record errors without suppressing them.

Local evidence: `M:/PortalDH-20260927/` (input hashes, bounded log excerpts,
owner screenshot, decompiled inspection, build logs, build proof and candidate).
The currently running Lab must be closed before replacement. Keep .7 for rollback.
No GregTest/Kinetic/Companion or ModSync inventory change is part of this trial.

## .9 correction: cloud fog after portal clipping

The .8 runtime submitted nonempty destination LOD buffers in both dimensions.
The owner's no-shader comparison then showed dense, sharply visible distant
clouds through the Nether-to-Overworld portal, fading away after crossing at
nearly the same facing angles. This is a visual discrepancy, not acceptance.

The installed DH 3.3.2 `DhApiRenderParam.update` caches the combined projection/
model-view matrix and its inverse. Our .8 tail hook replaced only the projection
and refreshed the API copy. DH's terrain and generic cloud shaders built their
draw matrix from the new projection, but `GlDhFogRenderer_neoforge.render` passed
the stale combined matrix to the fog shader for depth reconstruction. This is a
confirmed code defect consistent with the cloud comparison; live validation of
the correction is still required.

Candidate .9 updates the projection, combined matrix and cached inverse together
before refreshing the API copy. Two regression cases use the actual DH matrix
types and forward/reverse depth projections: the stale matrix misreconstructs
distant cloud/terrain positions by over 100 blocks, while the corrected matrices
and API copy recover them within one block. The change preserves the existing
DH fog policy, cloud settings and all other portal behavior for this comparison.
