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

## .20 candidate: distant clouds, AO depth and texture bands in portal views

The owner accepted the .19 shoreline/tree transition and shader aperture checks,
then reported dark beach patches and missing distant clouds without shaders.
Disabling DH's **Enable Ambient Occlusion** improved the beach shading. It did
not fully eliminate a very faint pattern of bands on sand. A second owner test
confirmed those bands disappear with **Enable LOD Textures** off. These are
separate effects; the AO correction alone does not claim to fix the texture bands.

DH's cloud-group culling reads Camera.getLookVector(), but IP moves the camera
position and applies portal rotation/reflection in the model-view matrix. This
mixes destination positions with source-world directions. Near cloud groups skip
the culling branch, so only distant groups disappear. A scoped direction derived
from the inverse DH model-view gives the culler the same view as the geometry.
The ordinary camera query, distance limit and back-facing rejection are retained.

DH's SSAO apply shader uses a scalar near/far depth formula for its bilateral blur.
Portal oblique clipping makes depth depend on screen position as well as distance.
A narrowly matched runtime adaptation of the installed shader adds inverse-matrix
depth reconstruction for supported no-shader portal passes. Every draw sets the
enable flag, so outer and sibling views cannot inherit it. The ordinary shader
branch remains unchanged. Unrecognized shader sources stay intact with a warning;
no DH shader copy or globally disabled setting is bundled in this fork.

The cloud regression executes the installed DH culling method with controlled
camera/config inputs. GPU tests execute the installed AO apply shader with a known
depth discontinuity: the old formula smears occlusion over the edge, while the
matrix branch matches direct projection in forward/reverse depth. A separate
comparison verifies unchanged ordinary shader output. These tests do not prove
that every cause of the owner's beach shading difference is repaired. Check the
same beach with AO restored, distant clouds before/after crossing, and the shader
regression again in Portal Lab before claiming live acceptance.

DH repeats block tiles with fract(vBlockPos), then uses implicit texture
derivatives to choose mip levels. Those derivatives jump across integer block
boundaries, introducing false bands. The .20 adaptation uses textureGrad with
derivatives of the continuous block coordinates, preserving each face's UV
orientation and the same atlas texel. It is enabled only for no-shader portal
passes and reset on every ordinary draw. An installed-shader GPU regression with
a mip-coloured atlas reproduces the bands, verifies continuous sampling for all
six face orientations, and checks unchanged direct-view output. It does not
replace portal TAA history or disable LOD textures.

Both DH 3.3.2 and 3.3.3 pass all **141 tests**, including **15 hidden-context GPU
regressions**, and produce the same native-preserving .20 JAR SHA-256
`ebc4e636ebeaa0df77fb94d21d75161cdf2c2fb2d79327e891628cd6ff67fb88`.
Compared with .19, three production classes change, six are added, and 1,022 are
byte-identical; the native DLL is unchanged. Evidence is retained under
`M:/PortalAudioCompat-20260929/render-followup-build/`. Full-game acceptance is
still pending at this build capture.

## .21 candidate: preserve depth while clipping portal geometry

The owner accepts .20's distant-cloud visibility and removal of faint sand bands
with LOD textures enabled. With AO enabled, dark patches remain and screenshots
516df1ef/6d8737b4 show missing contact shading under raised sand steps before
crossing. They also report cloud flicker only close to the portal plane, stopping
on crossing or backing away (dc47a49c/13208d21/14923417). Visibility and temporal
stability are separate acceptance criteria.

A new GPU reproduction renders a raised step into a real 32-bit float depth
texture, then executes DH's installed AO generation shader. The old oblique
projection adds false darkening and weakens the step shading. AO samples fetch
nearest-neighbour depth at a texel but reconstruct at unsnapped sample UVs;
oblique depth makes that mismatch strongly affect reconstructed distance.
Additionally, depth precision collapses as the near plane approaches the camera.
The .20 regression covered only the subsequent blur stage and did not catch this.

.21 leaves DH's ordinary projection, inverse and far plane intact without shader
packs. Small runtime adaptations of terrain and both direct/instanced generic
geometry shaders carry a signed portal-plane distance and discard the rejected
half-space. The plane is transformed into homogeneous clip coordinates, so this
also covers generic cloud object transforms, rotations, reflections and scale.
Each program bind uploads the current view plane or zero for ordinary views.
Fullscreen shaders and IP's aperture stencil retain their existing behavior.

The shader-pack path is unchanged. Active custom DH shader overrides or an
unrecognized required source retain the old oblique path, including .20's blur
correction. The normal-depth path uses the ordinary AO blur branch too, preserving
parity with the direct view. User AO, cloud, texture and anti-aliasing settings are
not disabled or changed. The .20 camera direction and texture-gradient fixes remain.

GPU controls reproduce the old AO mismatch at portal distances from five blocks
to 0.0001 blocks. The candidate's AO output matches the ordinary view, including
raised step edges; an opposite-facing clip plane rejects the whole test surface.
A second real-depth test puts two cloud faces two blocks apart at 1,000 blocks:
old oblique depth makes the visible face depend on draw order close to the portal;
normal depth consistently keeps the closer face. Both cloud modes and terrain
shader pairs compile, and ordinary/reverse depth plus bind reset behavior are
checked. These controlled tests are not owner-PC Minecraft visual acceptance.

Both supported DH artifacts pass all **150 local tests**, with **20 GPU checks**
and no failures or skips. The Java 21 builds produce the same .21 JAR SHA-256
`95c5686c98ad1df4600e3b9dcfe48061db66c1c93355ea5077b7a67634758b61`, preserving
the native DLL. Build logs and test XML are in
`M:/PortalAudioCompat-20260929/stable-depth-build/`. Installation, hosted CI and
owner acceptance are recorded separately in canonical context.

The owner subsequently confirmed that both beach AO/dark-patch differences and
close-portal cloud flicker are gone on .21. They also accepted the shader-on
destination terrain and aperture containment check. These are bounded acceptance
results for the reported cases, not a claim that every rendering difference is
resolved.

## .22 candidate: keep texture mips inside their material tile

The remaining no-shader report is darker terrain through the portal, especially
snow and other pale blocks. Disabling DH anti-aliasing did not remove it;
disabling LOD textures, with AO unchanged, made the before/after brightness match.
The exact DH 3.3.3 atlas generates a complete mip chain for its 16x16 tiles.
Beyond mip 4 a texel combines unrelated materials. The .20 continuous gradients
can select those coarser mips at distance, while the direct view still uses the
old wrapped derivatives. The two views therefore select different colours.

The installed-shader GPU reproduction with .21's adaptation turns a neutral 0.5
tile into 0.14901961 at a 64-texel footprint when neighbouring tiles are dark.
.22 caps the continuous footprint at mip 4, samples the two bracketing levels
explicitly, and confines each sample to its tile. It uses this policy in both
direct and portal views without shader packs. Shader packs retain their existing
sampling; absent uniforms are left alone, and toggling shaders resets the policy.

The regression covers distant footprints, six face orientations, exact inverted
UV boundaries and the previous sand-band failure. Compiled production upload
tests check view parity and shader toggles. No DH shader copy or setting change
is required. The .21 depth/AO/cloud implementation remains unchanged. Full-game
acceptance must still compare the same pale terrain with LOD textures enabled,
then confirm shader-on destination rendering and aperture containment.

Both DH 3.3.2 and 3.3.3 pass all **154 local tests**, including **22 GPU checks**,
with no failures or skips. Both builds produce .22 SHA-256
`76df747594b13b1a351c668798cdc652d0e341d2ab9d0a8a8b1a8a4ba9d8c896`.
Only the two texture-policy production classes differ from .21; the native DLL
is unchanged. Evidence is in
`M:/PortalAudioCompat-20260929/texture-brightness-build/`. Hosted CI, installation
and owner acceptance are recorded separately in canonical context.


## .23 candidate: isolated temporal AA for portal views

The owner accepts .22's texture brightness parity with LOD textures ON and
shaders OFF. With DH AA ON, snowy mountain speckles still diminish after crossing.
The prior compatibility path explicitly cancelled portal TAA and forced terrain
jitter to -1. DH 3.3.2/3.3.3 retain history textures in their renderer singleton,
but previous camera/matrix data in a separate shader singleton. Simply allowing
that shared renderer would contaminate both main and portal histories.

The portal path now uses the installed DH `taa.frag` and `sharpen.frag` resources
in separate GL programs, with the same uniforms, RGB10_A2 history format and
sharpen amount/composite policy. No DH singleton is changed or shader copied into
this JAR. Histories are keyed by source dimension, destination level wrapper and
the entire ordered portal UUID path. The complete path distinguishes siblings,
recursive views and reverse crossings. Client cleanup also separates sessions.

Terrain uses one phase of DH's existing eight-phase sequence per view/frame,
restoring the main terrain shader's counter in finally. Repeated uniform uploads
and repeated draws in the same outer frame do not advance history or phase again;
output becomes history only at the next frame. Previous matrices and camera are
captured with that output. Invalid history is explicitly black-cleared, matching
DH's no-history branch, rather than reading undefined texture storage.

A missed/incomplete frame, pause of at least 0.5 seconds, eight-block camera jump,
large view rotation, projection change, resize or depth-range change resets
history. Inactive views are freed after two seconds. The pool permits at most
eight views and 256 MiB of history textures, never evicting a current-frame view.
An over-budget view retains the prior unjittered fallback. AA/shader toggles, DH
renderer free and client cleanup delete targets/programs. GL bindings, samplers,
masks, enables, viewport and blend state are restored even on failure.

The new path is restricted to prepared ordinary-depth, no-shader portal views.
Shader packs and custom/unknown geometry overrides retain their .22 behavior.
The accepted texture, AO, clipping and cloud algorithms remain unchanged.

Regression tests run the actual installed DH TAA/sharpen shaders on a hidden GL
context: an alternating .2/.8 input produces .26 on its second accumulated frame,
and substantially smaller temporal variation after convergence. Separate portal,
nested and dimension keys retain distinct histories. Further checks cover stale
history, repeated frames, resize, eviction, cleanup, hostile GL state and unrelated
texture preservation. Compiled production mixin tests verify portal routing,
main-view passthrough, private phase upload, AA/shader veto and exception cleanup.
These controlled GPU checks do not certify the owner's full Minecraft image.

Both supported DH artifacts pass **164 tests, including 28 GPU checks**, with
zero failures or skips, and complete the Java 21 native-preserving build.
Both produce .23 SHA-256
`dc75502f8c5ad94f71d3b61de4ec732f317f2b6c548904aff5e4a9b41a0aef80`.
Compared with .22, five production classes change, six are added, 1,028 are
byte-identical and none are removed. The native DLL is byte-identical.
Evidence: `M:/PortalAudioCompat-20260929/taa-build/`. The first failed GPU run is
retained: its resize assertion incorrectly assumed deleted GL names could not be
immediately reused by the driver. Lifecycle checks now account for name reuse.
A one-time runtime log confirms when private portal history actually accumulates;
startup alone and these GPU tests do not prove the snowy mountain is accepted.


### .23 runtime rejection and .24 loader correction

After successful startup, the first .23 portal render on September 30 failed with
`Missing installed DH shader taa.frag`; DH disabled its renderer. The GPU test
classpath allowed cross-JAR resource lookup that NeoForge's named mod modules
did not. .23 is **not a usable/accepted runtime repair**. The failure log is saved
under `taa-build/deployment/first-runtime-failure.log`.

.24 uses `GlShader.class.getClassLoader().getResourceAsStream`, matching DH's
actual owning-loader lookup. A new regression loads the production pipeline
through a separate loader that cannot access DH resources and verifies both
shaders still resolve through DH's loader. Runtime acceptance remains required.


### .24 steady-view acceptance and .25 crossing candidate

The owner reports .24 portal and direct Overworld views look almost identical
with shaders off. A subsecond disturbance remains at the actual dimension
switch. Its resemblance to AA loss is a hypothesis, not a captured diagnosis.
The .24 runtime log confirms accumulation without the .23 resource error.

.25 transfers the completed private history of the successfully crossed portal
into DH's next main-history target, after DH allocates/resizes it and before
sampling. It copies unsharpened RGB10_A2 color and the matching previous camera
and combined matrix. The first main terrain sample continues the donor's phase;
repeated uploads use that same phase, then normal DH progression resumes.

A success-only teleport hook issues a one-shot ticket for the exact source,
destination and ordered UUID path. Same-frame combo teleports extend the path;
siblings, other dimensions and different nested paths are never searched as
alternatives. The donor must be completed within one frame and 0.5 seconds,
match viewport/depth range/projection/view, and remain within eight camera
blocks. An intervening overwrite, close, resize or invalidation rejects it.
A rejected crossing donor black-clears main history rather than sampling the
previous dimension. Ordinary frames leave DH's own main history untouched.
Shader packs and AA-disabled rendering retain their prior behavior.

Controlled GPU checks cover the color transfer and installed-shader continuation
(.2 previous plus .8 current yields .26 rather than a raw .8 flash), GL-state
restoration and stale/overwritten/closed donors. Contract tests inspect both DH
artifacts and the successful teleport hook; production mixin tests exercise the
camera/matrix adapter, both ping-pong targets, phase wrap and shader veto.
Runtime logs distinguish `portal crossing TAA history transferred` from a reset.
Evidence and dual-version build results: M:/PortalAudioCompat-20260929/taa-crossing/.
These checks do not establish that the owner's brief visual disturbance is fixed.


### .25 directional acceptance and .26 main-to-portal return candidate

The owner confirms forward Nether-to-Overworld crossing is smooth in .25, but
holding S with the portal behind the player still produces a brief white flash:
the camera keeps facing Overworld terrain while the player backs into the Nether.
The direct Overworld image becomes a portal image at that instant. The runtime
log alternates .25 main-history transfers and resets. .25 had no reciprocal
main-to-portal history handoff; no prior visibility of the return portal can be
assumed in this reproduction.

.26 records only metadata for DH's completed unsharpened main image, including
the actual terrain sample phase and the post-flip ping-pong framebuffer. At a
successful crossing it copies that image into the bounded private pool under
the linked reverse portal UUID and the reversed dimension pair. Copying happens
before DH renders/reuses its targets in the new dimension, not every frame.
The first compatible portal frame promotes that completed image as its history,
advances the matching phase and uses its original camera/matrix for reprojection.
The old DH main image can then be overwritten without affecting the return view.

Missing reverse links, stale main frames, shader/AA vetoes and wrong source
dimensions cannot donate. Existing projection/view/camera/depth/resize checks
still reject incompatible portal reuse. Only the exact top-level reverse path
receives main history; sibling/nested views cannot borrow it, and a combo teleport
cannot reuse the first dimension's main image for an unrelated intermediate one.
Normal .25 portal-to-main transfer and texture/AO/cloud code remain unchanged.
GPU tests cover overwritten main targets, continued sampling, repeated draws,
subsequent forward transfer, GL-state restoration, invalid sources, resize/jumps
and the unchanged memory bound. Runtime distinguishes capture from actual resume.
Evidence: M:/PortalAudioCompat-20260929/taa-return/. Visual acceptance remains pending.


### .26 flash acceptance and .27 speed-sampling correction

The owner confirms .26 removes the brief white flash in both crossing directions.
A separate two-second detail drop remains: the red-and-gold Nether tower becomes
coarse immediately on crossing, then recovers without movement, in both directions.

DH ClientApi compares raw main-camera positions and averages 40 speed samples,
spaced more than 50 ms apart. Its previous position is not dimension-aware. With
the installed reduceOverdrawWithFastMovement=true and DOUBLE_PASS fade, a speed
average above 10 blocks/s pulls the vanilla-to-LOD transition closer. The owner's
screenshots differ by approximately 100 vertical and 178 horizontal coordinate
blocks: such a jump can saturate the reduction until the bad sample rolls out
roughly two seconds later. This provides a testable explanation distinct from TAA.

.27 transforms only the previous sampling point through the successful portal's
full point transform (translation, rotation and scale), preserving its timestamp
and the rolling average. The next sample measures actual movement in the same
coordinate frame. No configuration change or blanket fast-movement disable is
used. Rejected teleports do not reach the hook. Uninitialized/nonfinite samples
are not installed; consecutive crossings compose transformations. Existing AA,
texture, AO and cloud algorithms are unchanged.

Tests execute the compiled production rebase method and the installed DH rolling
average. The uncorrected cross-world jump remains above 100 blocks/s for 40
samples; correction retains a 4-block/s walking average. Genuine 60-block/s
movement still remains fast. Tests cover both directions, rotation/scale,
consecutive crossings, timestamp preservation, invalid transforms and the exact
DH field/sampling/near-clip contracts in both supported artifacts. Live visual
acceptance of .27 remains required. Evidence: M:/PortalAudioCompat-20260929/detail-transition/.


## Portal vanilla coverage (.28 candidate)

Owner accepted .27 building-detail continuity on crossing. The subsequent seam appears after standing farther from a portal for 2-3 seconds and resolves closer to it. IP reduces destination loading radius at five blocks; tracking generations last 13 ticks and default unload retention is four generations. The previous DH wrapper always returned WorldRenderInfo render distance, even after smaller destination coverage unloaded.

Shaders-off DH portal passes now conservatively bound that radius by the nearest missing destination chunk footprint, measured from the transformed camera. The lookup uses FULL chunks with create=false and excludes EmptyLevelChunk; ClientLevel.hasChunk is not a reliable availability predicate. The value is cached only within a render pass, with no global cross-view cache or setting mutation. Main view and Iris shaders retain prior behavior. Partial coverage may move the transition nearer; this is intentional to avoid a hole, and visual acceptance remains required.

Regression cases cover delayed shrink/reload, off-centre cameras, missing interior chunks, negative coordinates, full coverage and non-creating runtime integration. Existing GPU regressions remain required on DH 3.3.2 and 3.3.3. Evidence: M:/PortalAudioCompat-20260929/boundary-transition/.


## .29 reversible visual frame blend

Owner authorized trying a cosmetic blend, after distinguishing it from physical bidirectional lighting. In the standard IrisPortalRenderer final composition, a 12-pixel band matches local colors across the top-level portal stencil boundary. Per-pixel RGB gain is bounded to 0.5-2; local same-side samples retain texture variation. Depth reconstruction rejects surfaces separated by >=0.6 blocks; sky and bright/emissive edges are excluded. It works on final display colors without rewriting shader pack files or requiring Colorful Lighting. It is not material-aware: nearby dark surfaces at the seam may also receive correction. Nested interior boundaries and the separate compatibility renderer are not blended in this first trial. Shader-family universality and visual improvement are not claimed until tested.

Resources are reused, reallocated on resize, freed when portals are absent or the client leaves its world, and cleared on failure. GL state and original framebuffer attachment are restored. Existing DH .28 source is unchanged. Trial acceptance requires inspecting upper cyan/lower warm frame colors with shaders on, ensuring no edge halos, and checking shaders-off DH fixes remain intact.
