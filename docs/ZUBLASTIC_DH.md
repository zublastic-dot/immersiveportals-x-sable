# Distant Horizons 3.3.2 / 3.3.3 portal views

## 2026-09-29: DH 3.3.3 activation repair (.18)

The owner updated Portal Lab to DH 3.3.3 and observed source-dimension LODs
inside the destination view in both directions with Complementary, and no
portal LODs with shaders disabled. The installed 3.3.3 JAR's SHA-256 is
`864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab`;
its bytes also match Modrinth release `9w34y8ai` by SHA-512.

The confirmed fork defect is `DhCompatibility.supports`: .17 admits only 3.3.2,
so updating DH silently excludes every `.dh.` compatibility mixin. This disables
the destination-view, lightmap, scoped-state and texture-isolation corrections
together. It explains missing no-shader LODs and is consistent with the reported
shader leakage; the repaired full-game image still needs owner validation.

.18 admits the inspected 3.3.3 release alongside 3.3.2. Uninspected versions
remain excluded, with a single explicit log warning; a supported version logs
that the portal hooks are enabled. No DH classes are initialized by the gate.
The inspected hook targets and call descriptors are unchanged. DH's changed
OpenGL target classes mostly route the same calls through its new LWJGL service;
the portal cancellation, level ticking, render parameters and LOD renderer
retain the inspected behavior. This is a version-bound correction, not a promise
that arbitrary future DH versions will work.

The dependency matrix tests the exact 3.3.2 and 3.3.3 artifacts. New contracts
verify that the tested JAR's declared NeoForge version activates our hooks,
that destination-view and opaque/deferred render targets exist, and that the
CameraZoom call still matches the isolation hook. The candidate compiles against
the older ABI. Local inspection/build evidence: `M:/PortalDH333-20260929/`.

Both local Java 21 test/build runs pass **128 tests, zero skipped**, including
12 hidden-context GPU regressions. Candidate SHA-256:
`b5d34afccbb5622e411ab40913a1e04a5a316b7ee5450c062b53234af431fa8c`.
Only the version gate and mixin plugin classes differ from .17; the other
1,022 classes and native payload are byte-identical. DH is not bundled. At this
capture .18 has not been installed or visually accepted.

Repeat both portal directions with shaders disabled, then Complementary. Verify
destination LODs, normal terrain outside the aperture, fog after crossing and
stationary stability. A passing contract or GPU test does not execute Minecraft's
transformed mixins or establish live acceptance. Jukebox audio cutting out on
crossing is a separate owner observation; this candidate does not repair sound.

## Original 3.3.2 investigation

Status on 2026-09-27: **PARTIAL_LIVE_ACCEPTANCE**. .9 cloud fade and .10 stationary
LOD stability are owner accepted for the tested no-shader view. Candidate .11's
outer-image isolation passes code/GPU tests but awaits installation and in-game
acceptance. This integration is inside IP/Sable; DH's JAR is neither edited nor
bundled. It builds on .7 without newly accepting or merging the earlier PR stack.

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

The original optional client mixins activate only for DH 3.3.2 (extended to the
inspected 3.3.3 release in .18 above). Other versions and absent DH retain their
existing behavior. The normal render hook supplies the destination
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

The owner subsequently confirmed "Clouds now match" after .9 was installed and
started in Portal Lab. This accepts the no-shader Nether-to-Overworld cloud
comparison; it does not accept all terrain, shader and moving-frame behavior.

## .10 candidate: stationary terrain flicker

The owner then reported Overworld LOD flicker while standing in the Nether,
including when the camera and both frames stay stationary. The screenshot
locates the affected terrain but cannot establish its temporal behavior. The
live client remains .9 with DH 3.3.2, shaders disabled and DH anti-aliasing on.

Code inspection found an incomplete pairing in the .8/.9 integration:
`MixinDhAntiAliasing` cancels the shared-history TAA resolve inside portals,
but DH's `GlDhTerrainShaderProgram_neoforge.fillUniformData` still advances
`frameIndexMod8` and uploads it to `uFrameMod8`. The terrain vertex shader uses
that index for subpixel jitter even when the camera is stationary. Animated
samples without temporal accumulation are a concrete code defect consistent
with the reported flicker, not yet a visually verified sole cause.

The .10 candidate uploads DH's unjittered sentinel (-1) after portal terrain
uniform setup. It restores the normal view's phase in `finally`, including
failed uploads. Ordinary views execute the original method unchanged. DH's
settings, JAR, per-view fog correction and prior portal repairs are retained.
This does not implement independent temporal-history buffers for each portal.

Regression tests execute the compiled production wrapper with only the live
portal flag and GL upload replaced by test boundaries. They cover all initial
sample phases, repeated portal passes, normal-view continuation and exception
propagation/restoration. Installed-artifact contracts check the exact fields,
method and shader's jitter guard. These tests do not constitute GPU acceptance.

Live comparison: stationary view with DH anti-aliasing enabled, then camera and
frame motion, both portal directions and crossing. The owner was also asked
whether temporarily disabling DH anti-aliasing stops the .9 flicker. Record
that result separately; do not globally disable the owner's option as a fix.
Evidence and candidate: `M:/PortalDH-20260927/flicker-repair/`.

All **81 tests** and the Java 21 native-preserving build pass. Candidate .10
SHA-256: `49ab34175d55a73020861ddb794b6f6fc70b4a684171b2d59f215174ca967421`.
At that build capture the candidate was not installed. The owner subsequently
accepted .10 in Portal Lab with anti-aliasing restored to true: "No flicker;
LODs are stable". Other 58 active JARs were unchanged.

## .11 candidate: destination clouds/terrain outside the aperture

Two owner screenshots establish a useful boundary control in .10: while in the
Nether with zero rendered portals, no Overworld leakage is visible. Moving right
to expose a sliver of the portal changes the counter to one and makes Overworld
clouds and green terrain visible outside the portal aperture. Shaders are off.
DH's configured vanilla fade is DOUBLE_PASS and its cloud list is Overworld-only.

The defect is not simply an absent stencil test in DH's final copy. The inspected
NeoForge wrapper uses its framebuffer copy path, which retains stencil testing.
Instead DH's singleton color/depth textures are overwritten by the nested view.
After IP returns to the Nether, `ClientApi.renderFadeTransparent` runs outside
the portal scope and `GlVanillaFadeRenderer_neoforge` samples those same textures.
Restoring `RENDER_STATE`, GL bindings and the portal mask does not restore pixels.
This explains how a later outer-view pass can blend remote clouds and terrain
across the screen. A diagnostic Vanilla Fade Mode=NONE comparison was requested;
no owner result is assumed at this capture.

.11 snapshots both DH images before the entire nested world render, then restores
their contents on return, including exception unwinding. Snapshots are pooled by
nesting depth, reallocated on size/format changes and released on client cleanup.
Copies preserve read/draw framebuffer bindings, texture/PBO bindings, scissor,
sRGB and stencil state. They do not touch Minecraft's live color/depth images.
Unexpected mid-pass target replacement skips a stale restore and logs a warning.
This scope is restricted to shaders disabled; Iris's shader/deferred targets keep
their existing path. No DH setting is globally disabled as a workaround.

Validation: **88 tests pass, 0 skipped**, with seven real GPU pixel regressions
using a hidden OpenGL 3.3 context on the laptop's AMD integrated GPU. One executes
DH 3.3.2's actual `vanilla_fade.frag`: overwritten shared input reproduces remote
pixels in the outer output; restoring the production snapshot yields the parent
pixels. The others cover nested/sibling views, exceptions, resize/recreation,
uninitialized targets, both color/depth contents and hostile GL binding/mask
state. This does not execute Minecraft's transformed mixins or establish visual
acceptance on its NVIDIA rendering context.

Run these optional GPU tests with `-PdhGlTests=true`; Windows native dependencies
are the default, with `-PdhGlNatives=natives-linux` available for a Linux GL host.
The ordinary test run skips the GPU class. The full native-preserving Java 21
build passed. Candidate SHA-256:
`83b6dba15e27f94c0a35c3cbb5fc1acf360599cd5d8096a0ca409bb429129a54`.
Native SHA-256 is unchanged from above. Evidence, screenshots and candidate:
`M:/PortalDH-20260927/mask-repair/`.

Live acceptance requires the same zero/one-portal edge comparison with shaders
off, AA on, and Vanilla Fade Mode restored to DOUBLE_PASS. Check both directions,
normal outside terrain, crossing, and stationary LOD stability. There are two
image copies each way per nested view (about 16 MiB of snapshot storage per active
nesting level at 1080p); performance in the full game must also be checked.

## Owner acceptance and shader follow-up

After .11 installation, the owner confirmed that clouds now render as intended
in the tested no-shader portal-boundary view. This accepts that reported case,
not every DH configuration or the shader path. The next owner comparison uses
Complementary Unbound r5.9.3: a far Overworld hill has less fog through the Nether
portal than after crossing. See `ZUBLASTIC_IRIS_DH.md` for the separate Iris
eye-light sampling diagnosis and .12 candidate.

## .19 candidate: sky-coloured near terrain at the DH transition

On DH 3.3.3 the owner reproduced a sky-coloured water/shoreline band and entire
tree silhouettes in a Nether-to-Overworld portal view with shaders off. The trees
render normally immediately after crossing. A subtle direct-view water seam,
and a dark water seam with Complementary/Euphoria, are separate observations;
they are not all assumed to have the same cause.

The installed `MixinVanillaFogCommon_neoforge.cancelFog` retains an unsupported-IP
fallback that forces vanilla distance fog on in portal views, even when DH's
Enable Vanilla Fog setting is off. `MixinDhVanillaFog` removes only that portal
veto. Quick Enable Rendering, the user fog setting, underwater/lava/powder-snow
fog, blindness and the wrapper's special-fog decision still run unchanged.

DH also skips its vanilla/LOD fade in portals. The compatibility enables it only
inside a scoped portal fade call, retaining DH's shader-pack veto. Both opaque
and transparent fade methods rebuild RenderParams, so both need the same oblique
projection adjustment as the terrain pass. The scope also disables geometry clip
distance for fullscreen shaders and restores GL state. An invalid projection
suppresses the fade draw, matching the terrain guard. Existing shared-image
snapshots continue to protect the parent view from nested portal rendering.

The fog regression executes the installed DH decision and the compiled production
hook with live game/config boundaries substituted. Its unpatched control reproduces
the portal-only forced fog; 128 combinations verify parity with the direct view
while retaining user/fluid/status/sky/special-fog conditions. Fade wrapper tests
cover nested scope restoration, direct views, invalid projections and exceptions;
installed-artifact contracts verify both parameter updates and draw call sites.
These are offline checks, not a transformed Minecraft launch or visual acceptance.

Acceptance requires the owner's same shoreline/tree view before and after crossing,
shaders off first, then checking that Complementary/Euphoria and normal terrain
remain intact. Portal audio and cross-dimensional lighting are separate work.

Both supported DH artifacts (3.3.2 and 3.3.3) pass all **135 tests**, including
12 hidden-context GPU regressions, and produce the same native-preserving .19
JAR SHA-256 `3670c747bca080968cc79d0d29d4ffa567c58dcee2f213f5785e51d4d0343927`.
Compared with .18, one production class changes and one is added; 1,023 others
are byte-identical. The native DLL retains its previous SHA-256. Evidence:
`M:/PortalAudioCompat-20260929/transition-build/`.
