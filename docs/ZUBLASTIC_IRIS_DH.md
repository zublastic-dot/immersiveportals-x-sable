# Iris destination fog sampling, 2026-09-27

The owner accepted .11's tested no-shader DH cloud boundary, then supplied a
shader-enabled comparison: a distant Overworld DH hill is clear through the
Nether portal and noticeably foggier after crossing. The instance uses DH 3.3.2,
Iris 1.8.14-beta.1+mc1.21.1 and Complementary Unbound r5.9.3, HIGH/default settings.
This is separate from the accepted no-shader matrix, jitter and image repairs.

## Source-backed mismatch

IP's `MyGameRenderer.switchAndRenderTheWorld` switches `client.level` to the
destination and supplies a transformed camera via `WorldRenderInfo`. It does
not move the actual camera entity. Iris `CommonUniforms.getEyeBrightness()` uses
that entity's X/Z and eye Y to query block/sky light from `client.level`. During
a portal pass these are coordinates from one dimension sampled in another.

The owner is around Y=45 in the Nether while the rendered Overworld camera is
above Y=106. An underground skylight sample can therefore be used for an outdoor
view. The actual light values in the owner's world have not been measured.
The installed shader pack defines `eyeBrightnessM` as smoothed
`eyeBrightness.y / 240.0`. Its atmospheric fog, including the DH depth branch,
is multiplied by `0.2 + 0.8 * sqrt2(eyeBrightnessM)` with cave fog enabled, or by
`eyeBrightnessM` without it. Incorrect low skylight can reduce the fog.

This is a verified sampling defect and a source-backed explanation of the
reported symptom, not yet proof that it is the only shader compatibility issue.
Iris rebuilds its own DH projection and matching inverse; the earlier no-shader
DH inverse-matrix correction is not reused as an assumed cause of this report.

## Candidate .12

A client-only optional mixin changes only the position argument used for Iris's
eye-light sampling while `WorldRenderInfo` is active. It uses the innermost
transformed camera position, including cross-portal camera views. Iris retains
its null guards, destination level queries, block/sky scaling and smoothing.
Outside that render scope, the original entity-eye position is returned unchanged.
No entity position, light array, shader source, DH option or temporal state is
mutated. Earlier DH and packed-depth/camera/moving-endpoint code is retained.

The hook is gated to the inspected Iris version above. No Iris or DH classes
are bundled or forked. Other Iris versions require a verified method contract
before enabling this particular hook.

Six regression cases execute Iris's dependency bytecode sampling method with
the compiled production argument hook, substituting only the live client,
entity and light-provider boundaries. A synthetic destination reproduces zero
skylight at the source coordinates and full skylight at the portal camera.
Tests cover normal-view parity, negative coordinate flooring without a second
eye-height offset, nested/moved views and unwinding, null guards and version
gating. They do not execute Minecraft's actual mixin transformation or prove
fog continuity in the owner's world.

The full Java 21 native-preserving build passes **94 tests, 0 skipped**,
including the seven retained DH GPU pixel tests. Candidate SHA-256:
`0831a50f746a31ac3544de7a0e9beb739779c82be4f74edcdfb78f2c04d29d66`.
All previously existing class bytes except `IPCompatMixinPlugin` match .11;
two new compatibility classes are added. The physics native is byte-identical.
At this build capture .11 remains running; .12 is not installed or live accepted.

## Provenance and live comparison

- Iris JAR SHA-256: `60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314`.
- Inspected `CommonUniforms.class`: `9cd8f45f63645cdaef6bbc6e1d5c43ae711078049b6a503fb80fcfb491cf5acf`.
- Shader pack SHA-256: `98ac741dee294d2a3c82d153349148c3bb7fb688c30eb3b49c3b9ac37e968c5a`.
- Local screenshots, hashes, decompilation and build receipts: `M:/PortalIris-20260927/`.

Live acceptance requires comparing the same far hill through the portal and
just after crossing with Complementary enabled, allowing its existing fog
smoothing to settle. Check the reverse direction and the normal view as well.
This candidate does not claim full Iris/DH compatibility, or repair unrelated
biome/weather uniforms and shader history across multiple distant portal views.
