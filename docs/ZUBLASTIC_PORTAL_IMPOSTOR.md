# Distant portal image cache

Candidate: `0.5.1-zublastic.46`, following `.45` source
`f7665d2ea50a6a0ad5f631f86945d469bc1cb444`. The owner authorized the image cache as a
separate distant-portal feature. `.44` live tests have not demonstrated a captured
or displayed image. `.45` passed 490 tests on each DH version and hosted CI and
was installed, but its live sunlight result was rejected: the portal room again
had no direct sun patch. `.46` corrects the actual portal placeholder block's
occupancy semantics and passes 491 local tests on each supported DH version.
Hosted CI, installation and live repair for `.46` remain pending.

## Behavior

An eligible portal that has been rendered normally can supply a small destination
picture. The client rectifies the completed image into aperture-local coordinates
and, farther away, draws it on the current portal plane. This preserves a distant
view without recursively rendering that destination every frame. It does not
render a far destination on demand, create a destination world, request chunks or
add chunk tickets. There is no picture for a portal that has never been captured
in this session. If the cache is unavailable, ordinary portal rendering and its
existing distance limits continue to apply.

The initial capture requires the whole aperture inside the view and at least
99.5% usable coverage. Shared framebuffer paths use the portal stencil so source
terrain cannot be baked into the picture. Dedicated destination buffers do not
require that source stencil. Later drawing uses the current aperture projection,
front-side visibility and current source depth; terrain in front still occludes
the image. The picture has no destination depth and remains a flat approximation.
Perspective changes, moving entities, time of day and destination edits can
therefore become stale until another ordinary live capture is possible.

The source Sable attachment uses its interpolated carrier pose, including the
flipped face, even after the portal entity is untracked. The carrier must still
be known to the client. Missing or changed source attachment data suppresses the
old picture. SSRD continues to render the frame separately: this feature neither
extends SSRD carrier tracking nor changes the camera far plane. The configured
maximum distance is an upper bound, not a promise that a picture will remain
visible through any renderer or carrier-tracking cutoff.

## Switching and bounds

| Setting or bound | Candidate behavior |
| --- | --- |
| `portalImpostors` | Enabled by default; disabling clears cached resources and retains normal rendering. |
| `portalImpostorLiveDistance` | Default 128 blocks; configuration clamps to 16..512. Actual cutoff is at most 85% of the engine's current live range. |
| Re-entry hysteresis | Return to live at the cutoff minus `min(8 blocks, 25% of cutoff)`. |
| Re-entry fade | 200 ms smooth fade after an actual fresh live destination render completes. |
| `portalImpostorMaxDistance` | Default 2048 blocks; configuration clamps to 64..2048. Server authorization also has a 2048-block bound from source origin. |
| `portalImpostorResolution` | Default 256; configuration clamps to 64..512 pixels per square image. |
| Retained images | At most 16; least recently used cached presentation is evicted at capacity. |
| Retained image storage | RGBA8: 4 MiB at the default size, at most 16 MiB at 512. Capture may temporarily allocate one additional image. |
| Coverage scratch | One framebuffer-sized R8 mask and 8-bit stencil attachment, at most 8,388,608 pixels; approximately 16 MiB maximum logical storage, excluding driver overhead. |
| Capture source size | Above 8,388,608 framebuffer pixels, capture is refused. |
| Ordinary captures | A global 250 ms throttle, plus a per-entry interval of `250 ms * max(2, entries + 1)`. |
| Boundary capture | Best-effort final live refresh when crossing the cutoff while still within the engine range; bypasses the normal capture throttle. |
| Metadata | 4096-character JSON bound; 16 server leases per player, 1024 total. |

Invalid/non-finite engine ranges disable distance-based cache admission. The pure
policy preserves engine headroom even for ranges below eight blocks. A cold or
invalid image never suppresses required live rendering.

On re-entry the live renderer starts immediately. The old image remains an
overlay until the completed-render callback proves that a new underlay exists;
only then does the 200 ms fade begin. Full-aperture recapture is deliberately not
required for this handoff: an approaching portal can be partly offscreen or
behind the near plane. No timer alone certifies a fresh live view. Repeated live
callbacks do not restart the fade, and invalidated images are removed immediately.

There is no periodic destination refresh while using only a distant image. Angle
or time based remote refresh was discussed as a possible extension and is not
implemented in this candidate.

## Authorization and lifetime

Both client and server need this fork's metadata protocol, including an integrated
server. Admission requires an eligible portal in an already loaded source chunk
sent to the nearby source-world player, and `broadcastToPlayer` permission. The
server uses existing entity indexes; metadata requests do not load chunks.

Client authorization lasts five seconds; the server checks and publishes it every
20 ticks. Clients renew every two seconds against a ten-second server lease.
Requests are limited per player. Entity untracking is not deletion: a missing
source is accepted as dormant only after the server witnessed its chunk unload.
An unexplained disappearance, removal, link or static geometry change, missing
dimension, expired lease or removed anchor invalidates the picture. Rigid motion
of an established anchored endpoint is permitted.

The server snapshots source and destination attachment fingerprints at admission
and compares them at validation. These identify the carrier, parent dimension,
local position and local orientation, independently of its moving world pose.
Reassigning either endpoint under the same portal UUID therefore revokes the old
lease, including while the source entity is dormant. This read-only lookup does
not load an entity or world. These checks are included in the passing `.44` matrix.

`PortalImpostorManager.RenderEvent` is the additional deny-only visibility hook
for cached faces, including faces with no tracked portal entity. Tracked faces
also use `PortalRenderer.PortalRenderingPredicateEvent`. Integrations whose
restrictions previously existed only in that entity-based client event must
handle `RenderEvent` for untracked pictures; server observation permission alone
does not reproduce arbitrary client-side restrictions.

Pictures are session-local and never written to the world save. Connection,
client world, renderer, shader pipeline/pack, resource reload and framebuffer size
changes clear them. Nested and shadow passes do not select or clear the outer
world's cache. A cache runtime failure disables this feature for the session and
leaves normal rendering available. A server that does not acknowledge a new
subscription within ten seconds also disables the feature for that session.

## Scope and known limits

The candidate supports top-level, visible, ordinary rectangular, zero-thickness
portals in the stencil, framebuffer, standard Iris and Iris compatibility render
paths. Mirrors, global portals, fuse views, irregular/thick portals and recursive
cached portal views are excluded. Experimental Iris rendering is not supported.
Renderer hooks existing in source do not establish compatibility with every
shader pack or its postprocessing.

The cached plane does not reproduce the destination's three-dimensional parallax,
fresh simulation, terrain depth, sunlight or shadows. Existing `.43` shader
lighting, ScalableLux, SableScalableLux and the SDL bridge remain separate systems.
This feature does not fork DH or SSRD, repair DH lighting, or prove that the two
white rooms have matching lighting.

## Evidence and verification

The completed `.44` local matrix includes the following automated coverage:

- `PortalImpostorPolicyTest`: engine headroom, malformed inputs, cold/invalid
  images, oscillation/hysteresis, completion-gated fade, repeated re-entry and
  monotonic-clock edge cases. Its 18 cases passed an isolated Java 21 run.
- `PortalImpostorProjectionTest`: projective rectification, transformed apertures,
  degenerate inputs, partial/near-plane visibility and preserved depth.
- `PortalImpostorGpuGlTest`: real GL capture-to-draw pixels, perspective gradient,
  source stencil/foreground exclusion, current terrain depth, target stencil,
  opacity, hostile-state restoration, query exclusion and resource cleanup.
- `PortalImpostorMetadataTest`, `PortalImpostorLeaseTest` and rate tests: bounded
  metadata, identity versus rigid movement, malformed input, witnessed unload,
  expiry, renewal, endpoint attachment reassignment and request limiting.

The two DH builds produced the same complete JAR SHA-256:
`8ec85c5e916ef52b11a7b4c78180e58d113ee204b39570ca24e0d5e201be1f66`.
Both result receipts and full XML reports are under
`M:/PortalAudioCompat-20260929/portal-impostor/build44/`; the final matrix completed
on 2026-10-02 at 12:25:32 UTC. Native test evidence was preserved. This verifies the
local source/build/test snapshot, not its deployment or live behavior.

The `.44` source was published in draft IP PR 28, passed exact-head hosted CI run
37007327221, and was installed in Portal Lab on 2026-10-02 at 12:37:45 UTC. Both
the installation receipt and original `.42` rollback JAR were preserved; other
mods and 4849 checked settings remained unchanged.

Two `.44` live routes did not establish cache activation. The first, lasting
52.422 seconds, showed a foreground pillar partly obscuring the aperture, making
the 99.5% coverage gate a plausible rejection. The elevated `live44-high-02` route
then showed a clean whole aperture at the near position but still produced no
capture log, and the distant left portal disappeared. Foreground obstruction
alone therefore does not explain the unresolved capture failure. Both runs used
six teleports including restoration, with zero time or block-change commands.
The elevated route saved all six screenshots and restored the player; its
`route_complete=false` flag records a final post-capture state read reaching the
conservative cleanup reserve, not failed restoration.

`.45` records capture rejections at most once per ten seconds: inactive/ineligible
renderer, invalid or partly offscreen aperture projection, unavailable GPU
capture, or measured usable coverage below 99.5%. These messages diagnose the
remaining issue; adding them is not a capture fix or proof of successful caching.

The related `.45` sunlight change packs explicitly observed portal-plane occupancy
into the finite shader atlas. The old final half-block DDA handling accepted only
25 of 100 rays in a source-clear reproduction. Known open plane cells now
participate in traversal; unavailable cells without retained observations and
solid cells remain blocked. Ambient interpolation has separate original-field
bounds so this occupancy padding contributes no ambient light or edge darkening.
The focused regression run passed 60 tests, including 14 GPU tests, and the new
regression fails with the pre-fix shader. The completed `.45` matrix then passed
490 tests on each of DH 3.3.2 and 3.3.3, with zero failures, errors or skips.
Both builds produced JAR SHA-256
`1cac23c2be662ad3eb94b1a7d33e3f00d7adb6275c7a64989c40b5d84bee1087`.
Receipts/full XML reports are in the evidence root's `build45/` directory;
the final run completed on 2026-10-02 at 13:38:12 UTC. Installed Complementary
Unbound r5.9.3 + Euphoria Patches 1.10.5 programs and actual GPU tests are included.
The native payload is unchanged. This source/test evidence does not establish
live checkerboard repair or successful image-cache admission.

`.45` was installed after exact-head CI run 37015598417 passed, but the owner's
shader-on screenshot at time 9000 shows a bright direct-sun patch in the ordinary
replica and none in the portal room. This rejects `.45` as a sunlight repair.
The live cause is the actual `PortalPlaceholderBlock.getLightBlock` contract:
it intentionally returns 15 to block vanilla light propagation. `.45` reused that
value for ray occupancy and classified the portal-plane placeholders as solid.
The synthetic open-plane regression did not exercise this actual block class.

`.46` recognizes the real portal placeholder only at the valid receiving aperture
plane with the matching axis, preserving its vanilla opacity of 15. Ordinary
solids and unrecognized/unobserved cells continue to block the ray. Actual
block-state regression coverage and the completed full matrix passed 491 tests
on each of DH 3.3.2 and 3.3.3, with zero failures, errors or skips. Both builds
produced JAR SHA-256
`371db6c7d6f3ca7e76cc1198e549f29a0a6fc717bc5bbe5a9cc1c1abb0059c72`.
The final matrix completed on 2026-10-02 at 14:32:48 UTC. The installed shader-pack
programs and actual GPU tests passed; the native payload remains unchanged.
Exact-head hosted CI run 37021686371 passed for commit
`0ce9c4d1c43b55361601e3abe67c2454c569e541`. `.46` was installed at 14:47:25 UTC
on 2026-10-02, preserving the other mods, 6105 checked settings, and the `.45`
rollback JAR. The owner subsequently observed sunlight, but also a bottom-up
loss of that sunlight after time 9000 and sharper shadow edges than the replica.
These reports prevent treating `.46` as a completed sunlight repair.

The source-clear CPU reproduction exposes the loaded-chunk boundary: as the sun
angle changes, lower aperture rows require source cells farther west to trace
to the build ceiling. Unobserved cells block those rays. The controlled fixture
produces 1024, 864, 544, 256 and zero lit samples with no solid obstacle, while
fully observed air stays lit. Live probes separately found air at x=-16 and an
unloaded chunk at x=-17, y=319, z=218. A stationary 30-second trial at time 9000
saved four screenshots and left the player's pose and inventory unchanged;
that particular trial remained lit and did not reproduce the owner's cutoff.
The source reproduction and observed boundary support the diagnosis, but they
must not be described as a successful live reproduction.

The `.47` candidate uses a completed source Iris opaque-depth snapshot and its
matching source camera, projection, sun direction and clock. Native-sized owned
images have a 64 MiB/four-image bound, are invalidated on lifecycle changes, and
expire after two seconds or 40 ticks. The supported pack's filter settings are
applied to the complete path: each sample checks the aperture, receiving-side
occupancy and source depth. Source depth is compared at the aperture, so source
geometry behind the opening cannot cast a fictitious receiving-room shadow.
The conservative CPU mask remains the fallback when a valid snapshot is absent.
Colored translucent source shadows are outside this candidate's opaque-depth
support. Receiving geometry uses voxel traversal and an approximate depth bias;
exact pixel-identical softness is not established. The complete `.47` matrix
passed 522 tests on each DH version, with zero failures, errors or skips,
including ten actual installed-pack GL compilation cases and the production
GPU tests. Both builds produced JAR SHA-256
`316942595d01e3b342db1fa161658af826c6b31b85a3718fc1d0ceb9d6af9eac`.
The final matrix completed at 17:02:42 UTC on 2026-10-02; evidence is in `build47/`
under the artifact root. Native payload hash remains unchanged. Hosted CI,
deployment and live acceptance are still pending.

No automated `.45` gameplay commands or flight ran: attempts stopped before
mutations because flight was disabled, user activity intervened, or the world
changed. Separate menu-controller actions are not gameplay verification. The user exited the
game at 16:24:31 CEST; the agent did not close it. `.45` cache diagnostics observed
only partly offscreen-aperture rejections during the user's near views. They do
not explain the earlier clean-view `.44` failure or establish cache activation.

Live acceptance still needs a recorded near/far/near flight, frame alignment while
the Sable carrier moves, ordinary terrain occlusion, source-entity untracking,
portal/link/carrier changes, shader/reload/world transitions, and a capture showing
that cached-only frames do not render the destination. Test partial apertures and
threshold crossings in both directions, including a cold cache and refused
authorization. Record cache diagnostics (`entries`, `captures`, `draws`,
`failures`, `disabledForSession`) alongside exact installed JAR identities.

Canonical checkpoint: zublastic-context
`history/2026-10-02-portal-impostor.md`. Local evidence root:
`M:/PortalAudioCompat-20260929/portal-impostor/`. `.44` and `.45` installations are
verified; `.46` installation is also verified and its sunlight repair remains
incomplete. Cache behavior, `.47` deployment and sunlight behavior, and
performance improvement are not live-accepted.
