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
