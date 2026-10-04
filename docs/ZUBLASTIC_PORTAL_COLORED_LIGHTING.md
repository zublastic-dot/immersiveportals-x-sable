# Experimental portal colored light

This client visual feature is opt-in (`experimentalPortalColoredLighting: false` in the Immersive Portals config by default). The same setting is exposed in the client config screen as **Experimental Portal Colored Light**. It is independent of experimental server/gameplay sunlight and does not change vanilla light storage, mob burning, or other server rules.

Use `/imm_ptl_client_debug portal_colored_light` for state, sampler limits, publication timing and rebuild queue diagnostics. Its `enable` and `disable` subcommands persist the client setting. Disabling retires published light and rebuilds affected terrain; it does not require a world restart.

## Rendering and source authority

Colorful Lighting 2.5.1 is the initially supported optional dependency. Both the mixin gate and runtime feature gate require this version and a working native engine. No dependency classes are linked when it is absent. Native Config definitions are queried through a LevelWrapper for the actual source ClientLevel, including configured block emitters whose vanilla emission is zero. Coordinates alone or dimension names do not identify caches.

Native channel values already carry intensity. Their arithmetic aperture mean includes dark cells and is not brightness-weighted a second time. Per-channel native propagation subtracts opacity (at least one) and applies the target transmission channel cap. The receiver uses an air-space field respecting its opening and obstructions. Transported RGB is max-merged with local RGB in Colorful's per-vertex sampling path and stored only in immutable render publications. The prior imported scalar block term is suppressed only for an endpoint with a ready RGB publication; ambient and sky corrections remain separate.

No synthetic sources are inserted into Colorful storage and imported RGB is never sampled as native source light. Facing portals therefore cannot amplify the contribution. Source changes, recolors, removal, and offscreen portals are refreshed through client ticks, not visibility/render calls. Loaded source chunks are read without requesting chunk loads or generation. Unknown source cells are opaque/dark, and unknown aperture cells cannot reuse cached bright scalar values.

## Bounds and current limits

The source sampler holds at most16 endpoint jobs, at most131,072 voxels per job, with a14-block native light support margin. All endpoints share8,192 native reads and65,536 propagation steps per client tick. Completed snapshots refresh after10ticks; partial captures are never published. The renderer exposes at most4 endpoint regions and recomputes at most5times/second. Mesh invalidation has a512-section queue cap and8 dequeues per tick; publication backpressure preserves necessary old-field cleanup instead of dropping it.

These are work limits, not an FPS guarantee. Actual cost and update delay depend on aperture size, changing geometry, loaded chunks, native definition lookup, and other mods. The initial feature handles placed block lights, rectangular unscaled portals and cardinal receiving planes across arbitrary loaded dimensions. Remote held-item/entity sources, scaled or arbitrarily rotated receiver volumes, global/irregular portals, and unloaded-world lighting are outside the initial scope.

## Shader border bloom

A frozen-time live A/B on the installed ComplementaryUnbound r5.9.3 + EuphoriaPatches1.10.5 pack showed the reported orange lower/right border vanished when native bloom was disabled. The correction supplies the actual active portal aperture to the bloom pass, rejects outside taps, limits mip footprints to the opening, and renormalizes accepted samples. It is independent of the sunlight region and dimension IDs. Main-view bloom is unchanged. Rectangular and nested rectangular apertures are supported; unsupported shapes or tiny apertures retain the native filter rather than guessing a mask. This is a pack adapter, not a claim of universal shader compatibility.

## Verification

Implementation and verification receipts are recorded in the associated task evidence and canonical context history. A successful compilation or unit suite alone does not establish in-game rendering or performance. Keep the experimental setting disabled unless deliberately evaluating it.
