# Real Camera and nested portal views

## Scope and source

IP/Sable `0.5.1-zublastic.6` adds an optional client compatibility hook for the
installed NeoForge 1.21.1 Real Camera `0.7.8-beta`. It is stacked on the `.5`
packed-depth repair; it does not modify Iris, Real Camera, the Sable native DLL,
server behavior or the owner's production mod lineup.

The owner reproduced broken portals in creative/survival/adventure but correct
portals in spectator in Portal Lab. Switching with F3+F4 reproduced the difference.
Rebinding only Real Camera's enable switch to F7 then toggling it isolated the
feature from FirstPerson's F6 binding. This observation concerns the missing or
incorrect destination view, not the separate AutoSeamBlend foreground-depth bug.

Reviewed source: xTracr/RealCamera tag `0.7.8-beta-1.21.1`, commit
`ef9e65ebe95b7f5022e40262c1e3ecbd5d697aaf`. Its camera and renderer hooks were
cross-checked against the installed JAR's `javap -v` output. The evidence and JAR
hash are in `M:/PortalRealCamera-20260926/provenance.json` and `upstream/`.

## Conflict and repair

Real Camera initializes global active/rendering state before each
`GameRenderer.renderLevel` camera setup. Its `Camera.setup` RETURN injection
then applies the cached player-model position/rotation and collision clipping.
Its `LevelRenderer` hook draws a first-person body using that same cache.

IP recursively calls `renderLevel` with a new camera and destination world,
without moving the real player to that world. IP's own camera RETURN injection
sets the transformed destination position. Real Camera can overwrite that
position and clip it using the player's source-world binding. Its body/camera
computation also runs in the wrong context. Merely changing injection order
would leave these other nested operations intact.

The optional pseudo-mixin intercepts RealCameraCore, not Minecraft camera order:

- While `WorldRenderInfo.isRendering()` is true, `isActive()` reports false to
  the nested pass. That skips the camera override, `computeCamera`, and the
  body pass (`isRendering()` delegates to `isActive()`).
- During that same interval, `initialize` is cancelled so the main view's state
  remains intact. No persisted setting or global active flag is changed.
- Once the existing IP render context ends, normal Real Camera behavior resumes.
  Nested depth is already represented by IP's stack; no second counter or restore
  flag is introduced. This also covers IP GUI/cross-portal world views.
- The hook is client-only and selected only for the verified Real Camera version.
  An absent or different release does not load it. Iris is not a prerequisite.

## Validation boundary

Production-hook regression tests exercise main-view pass-through, nested camera
suppression, initialization preservation, recursive depth and stack unwinding.
They do not prove framebuffer output. The subsequent owner test below provides
bounded visual acceptance for the installed candidate.

The full 59-root-JAR Portal Lab stack was restored before this repair, keeping
the owner's shader/configuration settings and replacing only IP `.4` with `.5`.
Its startup succeeded. The previous native observation was stopped by physical
Escape, so that run did not establish a full-stack visual pass.

## Owner test of the .6 candidate

Built implementation: `ce52ce6034845674359dcfd75cbe0b2bc4d2b72c`.
JAR SHA-256: `6e3d14e46f7dfb47a51d520e035a7cfee17455a4021583519284f202f2ab818d`.
All 41 regression tests and the bounded Java 21 build pass; the Sable native DLL
is unchanged. Installation retained all 58 other root artifacts in the 59-JAR
Portal Lab. Real Camera is enabled and ordinary Complementary Unbound r5.9.3 is
selected, with Sodium 0.8.13, Iris 1.8.14-beta.1 and DH 2.4.5-b present.

The owner built a new obsidian frame, assembled it into a Sable sublevel while
unlit, ignited it, crossed into the Nether, and looked back. The post-traversal
image visibly shows the Overworld coast through the portal. The owner separately
confirmed that the Nether also rendered correctly before crossing with Real
Camera enabled. The running log records the new isolation hook executing at
19:27:34.002 UTC, and the saved Real Camera feature remains enabled at 19:38:27.

This is owner-supplied acceptance of first ignition, one traversal and destination
rendering in both directions in this exact full Portal Lab stack. It is not an
agent-controlled same-view remove/restore test or acceptance of every portal
behavior. The native control attempt ended on physical Escape before this owner
test. Moving/scaled sublevels, repeated traversal, Euphoria and shaderless controls,
other versions/GPUs, and GregTest/Kinetic deployment remain unverified.

The existing DH event error, `An override already exists with the priority [10]`,
continues during this session; its repair and any performance claim are outside
this candidate. The AutoSeamBlend fix is retained, but a new full-stack occlusion
control was not supplied. Exact owner images, log snapshots/config and provenance
remain local under `M:/PortalRealCamera-20260926/owner-first-ignition/` and
`owner-return-view/`. Source PR 6 remains stacked above PRs 5 and 4.
