# Portal light refresh latency, 2026-10-01

Version .37 updated one directional field every five client ticks. Four active
fields in Portal Lab meant one update per field every 20 ticks. Fixed-coordinate
probes measured 435 ms and 357 ms from a source stored-light change during sideways
held-glowstone motion to the Nether CPU correction. The ordinary comparison-room
point responded within one sampled tick. These are CPU measurements, not photon
travel, rendered pixel or GPU presentation timestamps.

## Version .38 change

- Run every field's source sampling at NeoForge ClientTickEvent.Post, after the
  LevelTickEvent.Post update used by Sable's dynamic lighting.
- Separate dense topology, reachable-cell adjacency and 50-ray ambient visibility
  from brightness propagation. Cache topology and reuse it for moving lights.
- Recompute changed brightness from current seeds, starting from zero, so removals
  and weakening sources clear their old contribution. Unchanged seeds reuse the
  previous immutable field.
- Invalidate destination geometry on actual client air/solid block changes and
  chunk load/replacement/unload events. Dependencies include the entire grid halo.
  Cache chunk dependency sets so unrelated chunk events do not scan every cell.
- A source aperture chunk becoming available immediately wakes failed entries.
  Unsupported/unavailable initial fields otherwise retry every five ticks; valid
  fields do not use this retry clock.
- Portal movement, retargeting and ClientLevel replacement change field identity.
  Preserve measured geometry when live chunks are unknown, retaining .37's DH
  behavior; loaded data supersedes the retained measurements.
- Refresh each involved world's lightmap once per tick. Cache equal palettes and
  their 256 unweighted deltas rather than allocating temporary RGB arrays per cell.
- Publish a new immutable Region only when offsets change. Use per-world revisions,
  retain GPU atlas storage and upload only changed/reordered/removed 32-cube slots.
  Explicit draw and pixel-unpack GL state restoration remains in place.

## Bounds and limitations

The existing maximum of 16 directional CPU fields and four rendered regions per
world is retained. Geometry rebuilding occurs on relevant structural changes;
light changes alone do not rebuild it. .38 retains the existing vanilla lightmap
transport model, eligible axis-aligned unscaled portals, bounded transport volume,
and shader-pack guard. It does not add arbitrary shader-pack GI or per-emitter
Colorful Lighting RGB transport.

The public diagnostic getters lastUpdateNanos(), lastTopologyBuilds(),
lastPropagationCount() and lastPublishedRegions() describe the last complete
client update globally, not an individual sampled point. Duration includes all
work (including failed/unchanged fields); counts describe successful fields.
Bridge alpha.9 recognizes the exact .38 ABI; alpha.8 only recognizes .37.

## Verification

Regression tests exercise brightening/darkening, zero target reads during light-only
updates, cached result reuse, aperture/source identity, local opening/resealing,
partial unload/reload, and prior occlusion behavior. Hidden-context GL tests exercise
actual subregion uploads, unaffected slots, reordering/removal, independent atlases,
partial failures, and hostile pixel-unpack/PBO state restoration.

Live acceptance must repeat the midnight glowstone on/off and sideways probe trace,
check that topology-build counts stay zero during light-only motion, measure update
cost, and verify structural changes still block/unblock light correctly. Passing a
build is not live acceptance; exact hashes and test outcomes belong in the task
artifact report and canonical context history.


## Version .39: local Colorful ambient/emitter separation

The known Colorful Sodium path computes native sky/ambient plus powered RGB
emission multiplied by max(0.3, 1 - nativeAmbient.r). Previously, portal ambient
replacement was added after this attenuation. Removing warm Nether ambient
therefore left the local emitter attenuated by ambient that was no longer drawn.

Each immutable Region now contains total offsets and ambient-only offsets. Both
banks share one 32 x 32 x 256 RGBA16F atlas, four region slots per bank. Geometry
and source refresh bounds remain unchanged; a dirty slot uploads both banks and
partial failures require repair before metadata is published.

For the exact known packed Colorful ABI, the vertex adapter preserves native
ambient and decoded powered emitter RGB. Each occupied region applies:

    totalDelta + emitterRGB * (gain(max(nativeAmbient + ambientDelta, 0))
                              - gain(nativeAmbient))

The adjustment is calculated before overlap merging and uses this draw's emitter
values. No held-light ratio is cached. Vanilla, DH and Colorful fallback samples
retain the existing total-offset result. The shader-pack guard remains in place.

This is a local emitter correction, not complete source-RGB transfer. Across the
portal, block light still arrives as scalar brightness colored through the source
vanilla palette. Colorful's installed 2.5.1 engine has global storage and async
work reading mutable Minecraft.level; copying it under a guessed dimension key
would not establish source ownership. Complete RGB continuity needs explicit
world ownership and source lifecycle handling. The local gain correction alone
must not be advertised as completing that remaining work.

Bridge alpha.10 admits the unchanged public .39 field/timing ABI. Its legacy
ambient_offset_rgb remains the total CPU offset, not this draw-time gain term.
Live crossing acceptance is pending until a complete focused capture succeeds.


## .40 follow-up: ordinary lightmap composition

Live .39 crossing evidence (2026-10-01, runtime39-crossing.json) reproduced the
warm-to-cold change in both traversal directions across 215 captured ticks.
Current startup logs and installed bytecode establish that Colorful 2.5.1 rejects
Veil 4.3.2 and disables its entire colored engine; the actual terrain shader is
ordinary Sodium. Thus .39's Colorful-specific branch was not exercised in this
live test. See colorful39-runtime-disabled.md in the owner artifact directory.

Vanilla's dimension ambient affects the block-light response, including nonlinear
RGB curves, clamping and gamma. Subtracting the dark native floor N(0,0) does not
remove the ambient influence from N(s,b) at b>0. The current draw can therefore
become colder while CPU field offsets remain unchanged. This is distinct from
Colorful's dormant RGB propagation and world-ownership concerns.

The .40 compatibility policy is bounded to confirmed vanilla Overworld/Nether
lightmap models. Both directions select the captured Overworld palette as a
stable reference because its intrinsic dimension ambient is lower. The reference
is never chosen by a per-frame observed RGB minimum. Unsupported dimension pairs,
forced-bright models and shader packs retain the prior behavior.

For one region, let E(P,s,b)=P(s,b)-P(s,0), w be replacement weight, A the signed
ambient-only offset, and r denote transported levels. The ordinary path computes:

    candidate = currentNativeLight + A
              + w * (E(reference,max(s,r.sky),max(b,r.block)) - E(native,s,b))

The block/sky coordinates come from the current terrain draw. This removes the
native scalar emitter response and inserts a common response for the strongest
local/imported scalar level. It preserves the original interpolated native-light
residual, keeps ambient replacement separate, and avoids summing two copies of
the same scalar light during handoff. Matching occupancy and per-region metadata
are required before merging portal candidates. Palette sampling is bilinear;
interpolating scalar levels is not claimed identical to Minecraft's interpolation
of separately sampled vertex RGB at every fractional coordinate.

The single atlas expands to 32x64x256, keeping the GL3.3 minimum depth bound.
Existing total/ambient banks remain in y0..31. Transported sky/block/weight and
separate occupancy use y32..63,z0..127. Native/reference 16x16 palette slices use
y32..47,z128+32*region and z129+32*region. Paired publication and repair cover all
banks and palettes. Current held-light changes do not require reading local light
into the CPU field or rebuilding its topology.

Known valid Colorful RGB continues through .39's independent draw-time gain
correction. This is not a Colorful version override, a claim of arbitrary RGB/GI
transport, or a universal physical-light reconstruction from final RGB lightmaps.
Build and live acceptance for .40 must be recorded after implementation.

The gate requires exact Overworld/Nether dimension IDs and matching vanilla
effects IDs, finite intrinsic ambient values with Overworld strictly lower, and
neither effects model forcing a bright lightmap. Unknown, equal or reversed
models keep the .39 path. Valid nonzero decoded Colorful emission uses its .39
branch; zero/invalid packed-color samples use Colorful's vanilla fallback and
can therefore use .40 scalar correction.

Local .40 validation passed 337 tests without failures, errors or skips against
both DH 3.3.2 and 3.3.3, including actual framebuffer regressions. Bridge alpha.11
passed 58 tests. Live performance remains pending: the atlas now occupies 4 MiB
per world and four dirty regions upload 8 MiB of float input per update, including
palette-only changes. Retained topology caching does not establish unchanged CPU,
allocation or upload cost; .38 timing results do not measure these new costs.

## .40 runtime rejection and .41 receiving-endpoint eligibility

The installed .40/bridge alpha.11 crossing captured 215 frames with zero active
regions and revision zero. Its screenshots cannot establish color correction or
performance acceptance: the field never activated. The complete ordinary-room
comparison likewise does not validate an inactive correction.

Recent saved portal NBT matches the measured live position and has an Overworld
normal (-0.999516671067223, 0.000420215577159, 0.031084524727999), a 1.78146-degree
rotation. The old gate required both endpoint normals to have a dominant component
above 0.999999 (about 0.08103 degrees). The Nether receiving plane is cardinal,
but its remote source is not; both portal entities therefore rejected every field.

.41 admits each receiving endpoint independently. A rotated source is sampled
through the existing actual plane and full point transform. The receiving grid
must still be cardinal at the original strict tolerance, and topology construction
must still prove that every receiving seed occupies one integer coordinate plane.
The rotated receiving direction remains unsupported and falls back to native
lighting. No portal or world geometry is changed to satisfy the solver.

Unsupported orientation is reported when an endpoint first appears or changes
support state, with at most 32 remembered endpoint states. Normal motion within
an unsupported orientation does not log every tick. Existing field/size/global/
shape/scaling bounds and shader guards remain in force. Regression tests cover
the saved normal, both directions, full rotated-source sampling, opaque bounds,
and rejection of a nonplanar receiving map. Live activation, color continuity,
and cost require a new run; the game was handed back after the .40 run.


The .41 local matrix passed all 345 tests on each DH version, 3.3.2 and 3.3.3,
with no failures, errors or skips. Both produced the same JAR; the supplied native
DLL is unchanged. These results do not establish live field activation.

The paired .40 probe terminated after 11 frames when a menu opened, before the
six off/on/off room screenshots. Separate state/image receipts exist, but that
probe cannot measure their latency. The 215-frame crossing independently proves
zero active regions throughout. Future trials must verify nonzero regions and
point coverage before interpreting visual changes as evidence for this feature.
