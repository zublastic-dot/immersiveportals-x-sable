# Portal shader lighting

## Scope and source authority

The owner requested physical light transport into the Nether test room and made
ScalableLux plus SableScalableLux part of the Portal Lab baseline on 2026-10-02.
The previous `.41` correction intentionally disabled its vanilla RGB correction
when Iris shader packs were active. It did not create shader sunlight or shadows.

The `.42` candidate adds a separate shader path for the exact installed
`ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5` pack. Its source inspection
shows Nether direct sunlight disabled in the normal dimension branch, with
different ambient, directional shading and fog. A screenshot alone does not prove
a tint applied to the portal surface; this implementation changes the actual
receiving geometry's lighting before material/fog/final processing.

The adapter reads the installed pack through Iris's expanded include pipeline. It
reuses that pack's own Overworld color and option expressions in memory. The mod
distributes original transport code, not a copy of the shader pack. Missing or
ambiguous source anchors retain the original program with a bounded diagnostic.
Other shader packs are unchanged.

## Transport and rendering

- Actual portal transforms determine light direction, aperture coordinates, and
  source-space surface normals. The shader's observed `sunPathRotation` and clock
  branch determine sun/moon direction.
- Source-side rays check observed occluders between the aperture and sky. Each
  receiving fragment intersects the rectangular aperture, then traverses the
  receiving voxel volume. Opaque or unobserved intervening cells reject direct
  light; corner ties include all touched neighbors.
- The bounded aperture field supplies incoming sky and propagated block light.
  Enclosed areas replace native dimension ambient; openings to the local
  environment blend it according to measured visibility. Light amounts and the
  ambient replacement fraction are separate channels.
- Terrain, extended Iris shaders, Iris DH programs, and applicable fullscreen fog
  passes bind the field for the world actually drawn. Per-world atlases and
  draw-scoped texture/sampler restoration prevent cross-view GL state leakage.
- Nether border fog is reduced only for the field-covered portion of the view ray.
  Nether storm density is reduced at covered raymarch samples. Exterior, liquid,
  blindness and darkness fog retain their existing behavior.
- Held and other source lights are sampled from propagated block-light storage.
  A public dynamic-light query's distance-only fallback must not bypass source-side
  walls. The companion SDL update supplies virtual emission to ScalableLux's real
  propagation paths, including decreases and stationary-source replay.

## Current bounds

This is bounded compatibility support, not a general path tracer. It currently
supports Overworld-to-Nether transport through rectangular unit-scale portals with
a cardinal receiving voxel plane, at most four fields, 32-cell atlas edges, and a
16-block receiving depth. The source portal may be rotated. Source shadow masks
use a 32-by-32 aperture grid; full opaque blocks are the shadow occluders.
Arbitrary shapes, non-unit scaling, other packs, translucent-material transmission,
and generalized cross-dimension indirect bounce lighting are outside this candidate.

Source weather uses the aperture's loaded biome precipitation and source rain,
not Euphoria's smoothed per-camera weather history. Clear-weather comparisons
cannot establish identical rain-transition behavior.

The receiving calculation uses geometry normals and ordinary opaque-surface
lighting. Generated normal maps, subsurface foliage, colored glass shadows and
arbitrary PBR materials are not an exact-equivalence claim for this candidate.

## Validation record

Local matrix completed 2026-10-02: all **416 tests passed** against each of DH 3.3.2
and 3.3.3, with no failures, errors or skips. This includes seven actual installed
pack stock/patched compile-link cases, aperture/wall/corner GL pixel checks,
virtual-camera fog prefix checks, sampler-state regressions, and source cache
invalidation/retention tests. Both builds produced the same JAR SHA-256:
`d01c90c8ab10f84884ade11c0a16456823cad6011d778b1b8e8ff2ee909da8ee`.
The supplied Sable native DLL is unchanged.

The pure 32-by-32 shadow fixture read 18,065 world cells on first admission and
zero on an identical/unloaded repeat and nearby sun-direction update. These are
source-read counts, not live frame-time measurements. ScalableLux's raw nibble
reader was also verified to clone a 2 KiB array: source sampling now takes one copy
per section per update pass, preserving next-pass freshness and world identity.

The separate SDL `.2` candidate passed all 31 tests and hosted CI at
`fbba28d7f53fccdbd8f6a1262315ab8b610ee361` (Sable-Dynamic-Lights PR 2). Installation
completed for `.42`/SDL `.2` with ScalableLux and SableScalableLux; live acceptance remains pending. A successful build does not establish correct
in-game lighting.

Canonical task: `portal-shader-light-20261002`; base IP commit
`8c09f2d11501985ec82c3e8ecc0d9ec22c9aadf3`.

## Live activation correction (.43)

The `.42` runtime loaded the complete requested stack and bound the Nether shader fields, but direct sunlight remained inactive. Iris 1.8.14 JCPP replaces its source argument with preprocessed output before returning; the RETURN injection therefore lost the original dimension header. `.43` wraps the preprocessing call so the original header survives, then observes the returned option-expanded output. End shader settings remain excluded. A live daylight, occlusion and crossing trial is still required.

The `.43` local matrix passes all 422 tests on each of DH 3.3.2 and 3.3.3, with no skips/failures/errors. The six added regressions exercise the compiled wrapper around the real Iris preprocessor and verify the target argument-reassignment contract, Overworld/Nether observation, End exclusion, reload updates and error transparency. Both complete JARs have SHA-256 `6b89cb6d50095c0824efa683c34ecf369d0ec1a1c79d489833eec6eba620ac19`. Live transformed-Mixin activation and visual verification remain pending.
