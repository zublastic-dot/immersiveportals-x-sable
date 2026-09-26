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
They do not prove framebuffer output. Exact-candidate Portal Lab results are
recorded below after execution; visual acceptance is pending at implementation.

The full 59-root-JAR Portal Lab stack was restored before this repair, keeping
the owner's shader/configuration settings and replacing only IP `.4` with `.5`.
Its startup succeeded. The previous native observation was stopped by physical
Escape, so that run did not establish a full-stack visual pass.
