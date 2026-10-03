# Portal terrain handoff (.52)

The owner reported an empty band between full-detail Nether terrain and Distant
Horizons terrain when looking through an Overworld portal. In the .51 live
reproduction, the destination loader had radius two (25 chunks), while the
shader's `far` uniform still represented the main view's seven chunks (112
blocks). The virtual camera was only about 30.2 blocks from the closest loaded
edge. Complementary's DH alpha fade started at 44.8 and ended at 67.2 blocks.
That mismatch leaves real space where neither renderer supplies terrain.

## Change

The fork adapts the existing DH terrain and water fragment programs in the exact
supported owner pack, `ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5`.
The patch changes only their final near-distance alpha fade. It receives the
destination's available coverage through `ipDhCoverageBlocks`, uploaded after
Iris fills each LOD program's other uniforms. It does not replace `far`, alter
fog or camera projection uniforms, change portal sunlight, or change sound.

Coverage is the conservative distance from the virtual camera to the nearest
unavailable chunk footprint. Lookups never request, load, generate, or rebuild
chunks. With Sodium, an occupied section must have a built mesh; empty sections
need no mesh. This uses Sodium's public `isSectionBuilt` query, independent of
frustum visibility. Without Sodium the prior loaded-chunk criterion remains.
The scan is cached for each DH pass/requested radius and bounded to a 65-by-65
horizontal square; this bound can only bring LODs closer for unusually large
scaled portal views. A missing centre returns immediately.

The fade ends one block before the available coverage ends, or at the pack's
original fade end if that is closer. Its transition width keeps the original
ratio. When coverage is complete, the pack's original expression is used
unchanged. DH's native positive whole-chunk near-distance input uses the same
coverage measurement. This does not disable the normal depth test: early LODs
overlap behind existing full-detail terrain.

The shader correction applies only to first-layer portal views of the three
built-in dimensions with both terrain and water programs successfully adapted.
Main views, nested portals, source-shadow refreshes, shadow-map passes, unknown
packs and changed/ambiguous signatures retain their original shader behavior.
The upload explicitly restores an inactive value on main draws; it is independent
of whether any portal sunlight region exists. Iris reload clears adapter admission.

## Runtime diagnostics and A/B

Client commands:

```
imm_ptl_client_debug dh_portal_coverage
imm_ptl_client_debug dh_portal_coverage disable 30
imm_ptl_client_debug dh_portal_coverage enable
```

Disable accepts 1–60 seconds, automatically restores, and never writes a config
file. It disables the new shader-path fade and native near-distance correction;
the existing no-shader coverage path stays active. Diagnostics remain sampled
while disabled and report dimension, camera, requested chunks, main `far`, ready
distance, active fade endpoints, examined/missing/unbuilt column counts, sample
age, unique pass and shader adapter status. A sample can be stale when no supported
portal DH pass is currently drawing; its age must be checked.

For acceptance, keep position, time, shader options and loaded terrain fixed;
record correction off/on with shaders on, then shaders off. Verify the main view
and return from the portal separately. Build/driver tests cannot establish live
gap closure or acceptable overlap; those results belong in the runtime report.

## Limits

Loaded chunks are not proof that their meshes are ready. The whole-column mesh
criterion is intentionally conservative: an unbuilt nonempty section above or
below the view may reduce coverage to zero, causing extra depth-tested LOD
overlap. No promise is made that all 25 loaded chunks count as ready. Live
diagnostics distinguish missing from unfinished data. Fine-detail absence within
DH's own minimum near clip, missing DH cache data, arbitrary shader packs, nested
portals and custom dimensions remain outside this correction's guarantees.

Tests cover the measured offset footprint, interior missing/unbuilt data, bounded
scanning, scope restoration, finite A/B restoration, adapter identity/failure
paths, Sodium/Iris ABI, real driver fade pixels, and the owner's installed pack
through Iris include expansion, preprocessing, transformation and GL compilation.
The licensed shader source is only read from an explicitly supplied local path;
it is not copied into this repository. The optional installed Sodium JAR contract
uses `ip.portal.test.sodiumJar` and reads the nested mod in memory.
