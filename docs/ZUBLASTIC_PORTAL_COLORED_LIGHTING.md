# Experimental portal colored light

This client visual feature is opt-in (`experimentalPortalColoredLighting: false` in the Immersive Portals config by default). The same setting is exposed in the client config screen as **Experimental Portal Colored Light**. It is independent of experimental server/gameplay sunlight and does not change vanilla light storage, mob burning, or other server rules.

Use `/imm_ptl_client_debug portal_colored_light` for state, sampler limits, publication timing and rebuild queue diagnostics. Its `enable` and `disable` subcommands persist the client setting. Disabling retires published light and rebuilds affected terrain; it does not require a world restart.

## Rendering and source authority

Colorful Lighting 2.5.1 is the initially supported optional dependency. Both the mixin gate and runtime feature gate require this version and a working native engine. No dependency classes are linked when it is absent. Native Config definitions are queried through a LevelWrapper for the actual source ClientLevel, including configured block emitters whose vanilla emission is zero. Coordinates alone or dimension names do not identify caches.

Native channel values already carry intensity. Their arithmetic aperture mean includes dark cells and is not brightness-weighted a second time. Per-channel native propagation subtracts opacity (at least one) and applies the target transmission channel cap. The receiver uses an air-space field respecting its opening and obstructions. Transported RGB is max-merged with local RGB in Colorful's per-vertex sampling path and stored only in immutable render publications. The prior imported scalar block term is suppressed only for an endpoint with a ready RGB publication; ambient and sky corrections remain separate. With the supported Complementary/Euphoria shader path, a separate adapter supplies the missing lightmap brightness from Colorful's RGB peak before native lighting; the pack retains its native hue replacement. Shader admission is checked for the actual receiving world and terrain programs, and reset when its pipeline is destroyed. Unverified shader programs keep the scalar fallback instead of publishing dark RGB replacements.

No synthetic sources are inserted into Colorful storage and imported RGB is never sampled as native source light. Facing portals therefore cannot amplify the contribution. Source changes, recolors, removal, and offscreen portals are refreshed through client ticks, not visibility/render calls. Loaded source chunks are read without requesting chunk loads or generation. Unknown source cells are opaque/dark, and unknown aperture cells cannot reuse cached bright scalar values.

## Bounds and current limits

The source sampler holds at most 16 endpoint jobs, at most 1,024 aperture samples and 131,072 voxels per job, with a 14-block native light support margin. All endpoints share hard limits of 8,192 native reads and 65,536 propagation steps per client tick. A cooperative 2 ms sampler deadline is checked before every native read and flood step; at most one new snapshot is allocated per tick. A single native call, allocation or JVM pause cannot be preempted, so this is not a strict maximum tick duration. Receiver field updates and mesh rebuild notifications are outside that sampler deadline.

Only completed snapshots are published. A new refresh becomes eligible ten ticks after completion, so ten ticks is not a guaranteed response time. A 15 by 20 aperture's source box contains 59,856 voxels; four such jobs require 239,424 reads, or at least 30 ticks at the hard read cap before accounting for propagation, scheduling and the cooperative deadline. Slower native reads increase that delay. The status command reports actual sampler elapsed time/deadline exhaustion, known capture volumes, progress, source emitter counts and averaged RGB; total publication/update time is a separate metric.

Each snapshot reader caches at most 256 immutable block-state wrappers and native state-dependent filters. Installed Colorful 2.5.1 bytecode proves that its filter resolver ignores level and position; native emission and opacity remain queried per position. This cache is discarded with the snapshot, so later refreshes observe configuration changes. It does not cache world-dependent emission results.

The renderer exposes at most four endpoint regions and recomputes at most five times per second. Mesh invalidation has a 512-section queue cap and eight dequeues per tick; publication backpressure preserves necessary old-field cleanup instead of dropping it.

These are work limits, not an FPS guarantee. Actual cost and update delay depend on aperture size, changing geometry, loaded chunks, native definition lookup, and other mods. The initial feature handles placed block lights, rectangular unscaled portals and cardinal receiving planes across arbitrary loaded dimensions. Remote held-item/entity sources, scaled or arbitrarily rotated receiver volumes, global/irregular portals, and unloaded-world lighting are outside the initial scope.

## Shader border bloom

A frozen-time live A/B on the installed ComplementaryUnbound r5.9.3 + EuphoriaPatches1.10.5 pack showed the reported orange lower/right border vanished when native bloom was disabled. The correction supplies the actual active portal aperture to the bloom pass, rejects outside taps, limits mip footprints to the opening, and renormalizes accepted samples. It is independent of the sunlight region and dimension IDs. Main-view bloom is unchanged. Rectangular and nested rectangular apertures are supported; unsupported shapes or tiny apertures retain the native filter rather than guessing a mask. This is a pack adapter, not a claim of universal shader compatibility.

## Verification

Implementation and verification receipts are recorded in the associated task evidence and canonical context history. A successful compilation or unit suite alone does not establish in-game rendering or performance. Keep the experimental setting disabled unless deliberately evaluating it.
