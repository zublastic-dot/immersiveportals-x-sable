# 0.5.1-zublastic.37

- Restore hosted Sable structures after client world cleanup by requesting an authenticated viewer tracking replay. Normal visibility checks resend the complete structure; other players and rendering settings remain unaffected.

# 0.5.1-zublastic.36

- Preserve Sable collision bookkeeping when rejecting excessive entity movement, avoiding a null collision-record crash. The 60-block movement limit remains enforced. Includes the .35 held-light colour correction.

# 0.5.1-zublastic.35

- Keep newly selected held-light colors stable by applying portal ambient replacement additively to the renderer's current lighting, including Colorful Lighting. Preserve the bounded room field and DH support.

# 0.5.1-zublastic.34

- Replace the sealed-room lighting cutoff with bounded transport and local opening visibility; one wall hole no longer discards every interior correction.
- Preserve cached destination geometry and current lightmaps across unloads; known edits update the local field. Ordinary Sodium/DH terrain only; shader-pack and per-emitter RGB integration remain pending.

## 0.5.1-zublastic.33 - retain enclosure lighting across chunk unloads

Either portal endpoint now maintains both lighting directions. Verified enclosure geometry and measured light levels survive destination chunk/entity unloads for DH rendering, while current lightmaps keep time and weather responsive. Known geometry changes invalidate incomplete proofs; portal motion, retargeting and removal discard their mappings. This session-local cache requires an initial live measurement. Adds unload/reload, moved-aperture, local-lamp and partial-boundary regressions.

## 0.5.1-zublastic.32 � portal enclosure lighting trial

Adds a bounded client-only portal light field for enclosed air volumes, shared by Sodium and DH ordinary terrain rendering. Removes the local dimension ambient floor inside recognized enclosures and imports attenuated light from the linked opening. No world light data changes. Shader packs and per-emitter RGB transport remain unsupported; see `docs/ZUBLASTIC_PORTAL_LIGHT.md` for exact limits and acceptance checks.

# Changelog

All notable changes to this project will be documented in this file.  
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased Changes]

### Zublastic portal light boundary (.31 candidate)

- Stop ordinary block light and skylight from propagating through invisible portal placeholders. A light above a sealed portal room could previously travel around the roof edge and back through the aperture, even though that continuation of the local world is visually replaced by the destination.
- Keep portals invisible, noncolliding and nonemissive. Reuse the bounded existing-portal light refresh to remove saved light from the former transparent boundary.
- Add real Minecraft light-engine regressions for the measured glowstone route, several emitters, stored-light cleanup, internal sources, portal removal and horizontal skylight. Destination light transport and independent shader/mod lighting remain separate capabilities.

### Experimental shader portal-frame blend (.29 trial)

- Add bounded display-space color matching across the top-level Iris portal stencil seam. Preserve vertical color variation and texture detail; reject sky, depth discontinuities and bright edges.
- This is an owner-authorized cosmetic approximation, not cross-dimensional light propagation. .28 remains the rollback baseline.


### Zublastic portal vanilla/LOD boundary (.28 candidate)

- Bound shaders-off portal DH transition distance by destination chunks actually available around the transformed camera. Retained chunks may unload several seconds after stepping away from a portal; DH must not keep a near plane based on the larger main-view distance.
- Preserve .27 crossing correction and all user settings. Shader and main-view paths retain their existing distance.


### Zublastic crossing-induced detail reduction (.27 candidate)

- Rebase DH's previous speed-sampling position through a successfully crossed
  portal. Positions in different coordinate systems no longer look like a large
  movement spike that temporarily moves the vanilla/LOD fade closer.
- Keep DH's sample timestamp, rolling speed history, genuine fast-flight response
  and all user settings. Preserve the owner-accepted .26 bidirectional AA fix.

### Zublastic backward-crossing TAA continuity (.26 candidate)

- Preserve the completed main-view image for the exact linked reverse portal
  when backing through a portal while still facing the departing landscape.
  Copy only on crossing, before DH can overwrite its main targets; resume the
  matching camera/matrix and terrain sample phase in the new portal view.
- Reject missing reverse links, wrong dimensions, stale/disabled histories and
  incompatible viewport/camera/projection. Use the existing bounded history pool
  and cleanup. Retain .25's owner-accepted forward-crossing continuity.

### Zublastic portal-crossing TAA continuity (.25 candidate)

- Hand off the exact crossed portal's completed, unsharpened TAA history to DH's
  main view, including its previous camera/matrix and matching sample phase.
- Admit only recent compatible history from a successful teleport and the exact
  ordered portal path; reject stale, resized, overwritten or incompatible donors.
  Clear old main history when a crossing has no usable donor.
- Preserve .24 steady-view rendering, shader vetoes, GPU cleanup and prior
  texture/AO/cloud fixes. Log transfer versus reset for the fleeting crossing
  disturbance; full-game acceptance of this candidate remains pending.

### Zublastic DH shader resource loading correction (.24)

- Load TAA shader resources through DH's owning class loader. .23 passed the
  standalone GPU tests but its first NeoForge game run could not see DH assets
  through the IP module, causing DH to disable rendering with an explicit error.
- Add a separate-resource-loader regression and retain the isolated TAA design.

### Zublastic isolated portal temporal anti-aliasing (.23; failed runtime trial)

- Accumulate DH temporal AA in private histories keyed by source dimension,
  destination level and the complete ordered portal path, with matching per-view
  terrain sample phases. Main-view textures, camera history and phase stay intact.
- Run the installed DH TAA and sharpening shaders with bounded private GPU
  targets. Reset stale/discontinuous/resized history; release resources after
  inactivity, AA/shader changes, DH renderer shutdown and client cleanup.
- Cover accumulation, sibling/nested/dimension isolation, duplicate-frame draws,
  GL-state restoration and GPU lifecycle alongside the accepted .22 regressions.
  Preserve the shader-pack path. Full-game snow-speckle acceptance is pending.

### Zublastic destination texture brightness repair (.22)

- Limit DH texture mip sampling to each block's own 16x16 tile. Coarser atlas
  mips blend unrelated materials and can darken distant snow and pale terrain.
- Clamp each sampled mip inside its tile and use continuous gradients in both
  direct and portal views without shader packs. This retains the sand-band fix
  while removing the change of sampling policy at a crossing.
- Preserve shader-pack sampling and the owner's texture/AO/anti-aliasing options.
  GPU checks reproduce the old darkening and cover distant tiles, all six face
  orientations and exact tile boundaries. The owner accepts .22 brightness parity with LOD textures enabled and shaders OFF.

### Zublastic portal AO and close-threshold depth repair (.21)

- Keep DH's ordinary depth projection in no-shader portal views and clip terrain
  and both cloud rendering paths at the portal in their geometry shaders.
- Avoid the oblique depth reconstruction errors that lose contact shading and
  produce false AO on flat sand, and the depth precision collapse near a portal
  that makes distant overlapping cloud faces depend on draw order.
- Reset the clip plane for every shader bind and retain the existing oblique
  path for shader packs, active custom shader overrides, or unknown DH sources.
- Regressions render actual depth and execute DH's installed AO and cloud
  shaders, including step-edge shading, rejected geometry and near-threshold
  cloud depth order. Owner acceptance remains required.
- All 150 local tests/build pass on DH 3.3.2 and 3.3.3, including 20 GPU regressions.

### Zublastic portal clouds, ambient occlusion and LOD textures (.20)

- Supply DH cloud culling with the destination camera direction, including portal
  rotation, reflection and scale. Preserve its distance and behind-camera culling.
- Reconstruct depth in DH's ambient-occlusion blur from the portal projection.
  Its ordinary near/far formula cannot describe the angled portal clipping plane.
  Keep the direct view's shader behavior and the user's AO setting intact.
- Calculate LOD texture mip gradients before block-coordinate wrapping to avoid
  false bands at tile boundaries in no-shader portal views. Keep textures enabled.
- Add exact installed-DH cloud-culling and GPU blur regressions for forward and
  reverse depth, plus texture sampling checks for all six block faces. Visual
  acceptance is recorded separately: .20's distant-cloud visibility and texture
  bands pass; beach AO remains unresolved and motivates .21.
- All 141 local tests/build pass on DH 3.3.2 and 3.3.3, including 15 GPU regressions.

### Zublastic portal fog/LOD transition repair (.19)

- Honor DH's vanilla-fog setting inside supported portal views, retaining fluid,
  blindness and special fog. DH's old unsupported-portal fallback forced distance
  fog onto near terrain and produced sky-coloured trees and shoreline.
- Run the no-shader vanilla/LOD fade in a portal scope with matching depth
  reconstruction and restored GL state; retain DH's shader-pack veto.
- Add installed-DH fog-policy and scoped-fade regressions. Full-game image
  acceptance remains required; audio and portal illumination are separate work.
- All 135 tests/build pass on DH 3.3.2 and 3.3.3, including 12 GPU regressions.

### Zublastic DH 3.3.3 compatibility activation (.18)

- Enable the existing portal-view compatibility hooks for the inspected DH 3.3.3
  release. The previous version gate silently disabled them after the update.
- Log the DH compatibility decision once so future unsupported updates are
  visible, retaining the explicit gate for uninspected versions.
- Test both 3.3.2 and 3.3.3 artifacts in CI, including the actual mod version's
  activation and destination-view/render-call contracts. Both local builds pass
  128 tests, including 12 GPU regressions. All changes stay in IP/Sable; live
  portal comparisons remain required.

### Zublastic cached GL allocation follow-up (.17)

- Cover IP's own cached early returns when initializing GL buffers and VAOs.
  The .16 live test still logged 22 GL errors because its return-value injector
  missed that path. Wrap the complete allocator without disabling the cache.
- Add a GPU regression that invokes the production wrappers with batch-reserved
  names and verifies debug labels and direct-state-access uploads.
- All 125 tests/build pass, including 12 hidden-GPU tests. Live verification of
  the cached path and fresh in-world sound/assembly checks remains pending.

### Zublastic Portal Lab log repairs (.16)

- Dispatch off-thread portal sound calls to the client thread, dropping queued
  sounds if their world unloads before playback.
- Exclude Simulated's fullscreen diagram outline from world-geometry clipping;
  match Veil transformations by shader stage to avoid false failure warnings.
- Instantiate freshly generated GL buffers and vertex arrays before Veil labels
  or uploads them, preserving bindings and unrelated GL errors.
- Keep the first assembly tick on the ordered connection while sublevel hosting
  is active, preventing local/UDP movement from racing allocation or rehome.
- All 124 tests pass, including 11 hidden-GPU tests. Live verification of the
  repaired session errors remains pending. See `docs/ZUBLASTIC_LOG_REPAIR.md`.

### Zublastic carried portal dimension handoff

- Transfer a Sable frame's attached portal faces with it when the frame crosses
  another portal into a different dimension. Preserve portal identities and
  the original far-end connection, and save the attachment's new dimension.
- Stage all carried faces before retiring originals; reject incomplete handoffs
  before moving the frame. Keep the portal being crossed outside this transfer.
- Cancel stale client animations before applying their final state after a
  portal's origin or destination dimension changes (.14 crashed on this path).
- Exclude invisible portal-surface blocks from the occupied crossing volume:
  lighting a hollow frame must not make its opening count as a solid sheet.
- Candidate .15 passes 111 tests and addresses the observed crash and a false
  crossing-volume defect; live small-through-large passage and the reported pushing still need
  verification. See
  `docs/ZUBLASTIC_CARRIED_PORTAL_TRANSIT.md` for behavior and validation limits.

### Zublastic portal surface outline preference

- Add the client setting "Show Portal Surface Outline", enabled by default.
  Turn it off to hide only the invisible portal surface's selection outline;
  normal block outlines and targeting remain unchanged. Save to apply in-game.
- Expose the existing config screen through NeoForge's Mods menu. The existing
  `/imm_ptl_client_debug config` command also opens it. See
  `docs/ZUBLASTIC_PORTAL_OUTLINE.md` for behavior and validation scope.

### Zublastic Iris portal fog sampling

- Sample shader eye lighting at the transformed destination camera during nested
  world views. Iris previously queried destination light at the source character's
  coordinates, which can suppress Complementary fog over distant DH terrain.
- Retain normal-view sampling and Iris smoothing; enable the optional hook only
  for the inspected Iris 1.8.14-beta.1+mc1.21.1. Candidate .12 awaits the owner's
  same-hill comparison. See `docs/ZUBLASTIC_IRIS_DH.md` for evidence and limits.

### Zublastic Distant Horizons destination views

- Add optional DH 3.3.2 integration inside IP/Sable: destination LOD ticks,
  dimension-correct wrappers/lightmaps, scoped rendering and portal-plane clipping.
- Keep DH's cached combined/inverse matrices consistent with portal clipping,
  correcting the stale distance reconstruction found in the .8 cloud comparison.
- Disable DH terrain sample jitter inside portals where shared temporal history
  is skipped; preserve the normal view's anti-aliasing phase and settings.
- Preserve DH's outer color/depth images across nested no-shader world renders,
  preventing its late vanilla fade from reading the portal destination's images.
- Preserve the actual-player view and previous camera/depth/moving-frame code.
  The owner confirmed .9's cloud correction and .10's stationary LOD stability.
  The owner also accepted .11's tested no-shader cloud boundary after 88 passing
  tests including seven GPU pixel cases. Shader compatibility is a separate test.
  See `docs/ZUBLASTIC_DH.md` for evidence, limits and the Portal Lab trial.

### Zublastic dual moving portal endpoints

- Give each physical portal frame its own Sable attachment and derive one coherent
  mapping from both carrier poses on the server and client. Preserve the two faces
  of each frame, single-end behavior, and the prior camera/depth corrections.
- Retain the surviving endpoint's current destination when the other detaches,
  and save attachment snapshots independently of runtime teardown.
- Candidate .7 passes 56 code tests and a native-preserving build; in-game dual-end
  acceptance remains pending. See `docs/ZUBLASTIC_DUAL_ENDPOINTS.md`.

### Zublastic Real Camera portal isolation

- Preserve Real Camera's normal first-person camera while excluding its player-bound
  camera and body pass from IP's nested world views. Keep its main-view state intact
  when recursive rendering calls its initialization hook.
- The optional client hook targets Real Camera 0.7.8-beta on NeoForge 1.21.1 and
  retains the packed-depth fix. See `docs/ZUBLASTIC_REAL_CAMERA.md` for evidence
  and the current visual validation boundary.

### Zublastic packed portal depth compatibility

- Match IP's portal scratch depth/stencil format to a packed source changed by
  UI integrations, including AutoSeamBlend's bundled ApricityUI. This preserves
  foreground depth instead of attempting incompatible depth copies.
- Query actual texture storage even when IDs stay unchanged, retain the source
  image and restore texture/framebuffer bindings. See `docs/ZUBLASTIC_PORTAL_DEPTH.md`
  for verification boundaries and runtime acceptance.

### Zublastic client clipping candidate

- Keep IP/Sable clip distances disabled for copy shaders that do not write them;
  restore the correct planes for geometry and both Sable cuts.
- Make explicit clipping resets reach GL and invalidate uniform locations on
  Mojang program deletion/relink. Visual acceptance remains pending; see
  `docs/ZUBLASTIC_PORTAL_RENDERING.md`.

Atlas hardening since the 0.5.0 merge (PR #15):

### Fixed

- Multi-portal straddle: contact clipping is aperture-bounded again — the 0.5.0
  full-plane clip let two sessions' half-spaces swallow a ship, and parts beside a
  free-standing frame lost ground collision.
- Ships whose parent dimension is unloaded (e.g. a nether ship while everyone is in
  the overworld) fell through the world. They now go dormant (native Fixed body,
  zero tick cost) and wake in place when a player loads the area; no chunks are
  force-loaded.
- Ships in different parent dimensions collided when their coordinates overlapped
  (per-body parent-frame check in the native dispatcher).
- Connected ships got stuck on the wrong logical side of a portal: the per-member
  transit gate deadlocked once the first member crossed (gate removed).
- A roped partner no longer teleports through together with the crossing ship.
- Modded packet handlers resolve hosted-ship positions correctly: deferred
  world-frames wrap payload dispatch, arming on first hosted-plot resolution
  (fixes Simulated assembly interactions and Photomancy blueprint capture).
- Disassembly desync: block-restore notifications on a hosted ship routed to the
  hosting level (no chunk holder) and vanished; the client never saw the ship
  turn back into blocks.
- Teleports targeting the hosting dimension (e.g. Waystones placed on ships)
  redirect to the ship's parent dimension; Waystones' own distance check resolves
  ship-frame waystone positions through the delegate dimension.
- Entities parked at plot coordinates in a parent level (Simulated's ship-attached
  plungers) were garbage-collected by vanilla's entity-chunk unload within
  seconds, orphaning their physics joints. Three layers: plot-ticking memberships
  ignore the id-aliased client copy (vanilla Entity.equals compares by id, so
  singleplayer client lerps corrupted server state), plot-parked entities are
  exempt from chunk store/unload (player semantics), and entity-chunk visibility
  downgrades on live plot chunks re-assert ENTITY_TICKING.
- Client copies of plot-parked entities never ticked (no real parent client chunk
  at plot coordinates), freezing attach animations and orientation — the plunger's
  rope spline rendered permanently mid-connect. The client now mirrors the server's
  plot-chunk entity-ticking bridge.
- Plunger rope visuals: paired plungers no longer draw their rope to the world
  origin when the partner's client entity is transiently missing (Simulated zeroes
  its synced target client-side every tick; the synced value is restored), and
  client-side kicks through a not-yet-synced sub-level pose are skipped instead of
  teleporting the entity to ~(0,0,0).

### Changed

- Rigid assemblies (swivel bearings, fixed couplings — joints locking an angular
  axis) cross portals atomically as one unit.
- Ropes span portals: a crossed ship's rope routes through the aperture and pulls
  the trailing body toward it; the chain follows once both ends cross, and backing
  out unwinds. Ropes overstretched past 1.75× natural length (snagged trailing
  body, cross-dimension splits) break, vanilla-lead-style.

### Known Issues

- Rare missed rope re-unification when both ends cross the same portal within a
  tick (diagnostics in place).

## [6.0.7] - 2025-06-18

### Fixed

- Oritech animations not displaying ([#13](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/13)).
- ComputerCraft monitors not displaying anything ([#31](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/31)).

## [6.0.6] - 2024-12-22

### Updated

- Sync upstream (v6.0.6)
- Sodium compat (v0.6.0)
- Iris compat (v1.8.0) (experimental)

### Fixed

- Default config values being wrong

## [6.0.3] - 2024-10-20

### Added

- Initial port to NeoForge 1.21.1

### Known Issues

- Iris compatibility is not fully functional
- Crash with SecurityCraft

[Unreleased Changes]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/compare/v6.0.7...HEAD
[6.0.7]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.7
[6.0.6]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.6
[6.0.3]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.3



### .30 � remove invisible portal light emission

Portal placeholder blocks now emit zero block light. Existing breakable portal entities recheck their placeholder lighting once per entity load, at most 64 positions per tick, deferring unloaded chunks without force loading. Sable frame coordinates use the plot-hosting level. This removes the old fixed level-15 lamp; it does not implement destination-colored lighting or alter shader frame blending. Existing orphan placeholders without a portal entity are not proactively scanned.
