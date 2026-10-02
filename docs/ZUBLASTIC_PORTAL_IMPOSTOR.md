# Distant portal image cache

Candidate: `0.5.1-zublastic.45`, following installed `.44` source
`6432bf24897f543162f3d6e662c3393b0f39c9ea`. The owner authorized the image cache as a
separate distant-portal feature. `.44` passed 478 tests on each DH version and
hosted CI, but live tests have not demonstrated a captured or displayed image.
`.45` adds capture-rejection diagnostics and fixes a separately reproduced
sunlight checkerboard defect. Its full local matrix passed 490 tests on each of
DH 3.3.2 and 3.3.3, with zero failures, errors or skips. Hosted CI, installation
and live validation are pending.

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

The related `.45` sunlight fix packs explicitly observed portal-plane occupancy
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

Live acceptance still needs a recorded near/far/near flight, frame alignment while
the Sable carrier moves, ordinary terrain occlusion, source-entity untracking,
portal/link/carrier changes, shader/reload/world transitions, and a capture showing
that cached-only frames do not render the destination. Test partial apertures and
threshold crossings in both directions, including a cold cache and refused
authorization. Record cache diagnostics (`entries`, `captures`, `draws`,
`failures`, `disabledForSession`) alongside exact installed JAR identities.

Canonical checkpoint: zublastic-context
`history/2026-10-02-portal-impostor.md`. Local evidence root:
`M:/PortalAudioCompat-20260929/portal-impostor/`. `.44` installation is verified;
cache behavior, `.45` deployment, performance improvement and completed lighting
correction are not live-accepted.
