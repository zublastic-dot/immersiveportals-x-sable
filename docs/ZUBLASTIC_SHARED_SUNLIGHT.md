# Experimental shared sunlight profile and gameplay (.54 candidate)

Status: full local build/test matrix passed; runtime verification pending.
Build head `4116395a7adb7304648fe3194ceb40712827082b` passed 651 tests on each
of DH 3.3.2 and 3.3.3, with zero failures/errors/skips, including 17 tests using
the actual supported pack. Both release artifacts have SHA-256
`68d710edefa3c650ad20fff30090b40b92c9f364353f06b6e01c325fe810f2a2`.
This build evidence is not installation or live acceptance. The canonical
2026-10-03 portal video/DH history records deployment and acceptance separately.

This is an optional experimental per-world setting of the fork, disabled by
default. An operator must explicitly opt in. Disabling it restores the existing
native gameplay and lighting behavior without the shared-profile override.
Portal Lab tests must restore the experimental setting to disabled afterward.

When enabled, the profile makes the server the authority for sunlight direction. Supported
clients render that same trajectory, and server mob exposure uses it even when
someone disables shaders or looks away from a portal. A local shader setting is
not automatically allowed to change gameplay for everyone.

## Enabling and inspecting a profile

The feature starts disabled in a world that has no saved profile. Commands require
operator permission level 2:

```text
/imm_ptl_sunlight status
/imm_ptl_sunlight profile <rotation> <SUN_ANGLE|WORLD_TIME>
/imm_ptl_sunlight enable
/imm_ptl_sunlight disable
/imm_ptl_sunlight probe
```

`profile` sets and enables the profile. Rotation is a finite number from -180 to
180 degrees. `SUN_ANGLE` uses the supported pack's remapping of Minecraft's sky
angle; `WORLD_TIME` uses the day clock directly before the shared trajectory
calculation. Neither choice changes world time. `enable` keeps the stored angle
and clock; `disable` turns off this gameplay override and client profile override.
`status` reports profile/revision. `probe` samples the command source's current
position and reports strength, path, reason and geometry read budget; it is a
point sample, not a mob-health, burning or rendered-pixel measurement.

An operator using the supported active shader pack can inspect and explicitly
publish its observed native trajectory:

```text
/imm_ptl_client_debug sunlight_profile
/imm_ptl_client_debug sunlight_profile publish
```

Publication requires a live server connection, permission level 2, enabled
shaders, the exact supported pack, and a native profile observed during shader
preprocessing. Observation precedes the server override, so publication does not
accidentally import an inherited server profile as the owner's own preference.
The client reports submission; server acceptance and synchronized diagnostics
are the completion evidence. The server authenticates the sending player,
validates the profile and limits client imports to once per 20 server ticks.
Accepted publication enables the experimental setting for that world. It is an
explicit opt-in, not background settings synchronization. Use `disable` to opt
out again.

## Persistence and synchronization

One profile is stored per world save in the Overworld's SavedData
`data/imm_ptl_sunlight.dat`, with a schema and versioned revision. It is not a
machine-global preference or an individual player's option. Login, respawn, dimension
change and operator profile changes send the server profile to clients. Invalid
or incomplete saved data leaves this feature disabled and logs the problem.
A client request does not bypass the server's validation or become authoritative
until the server accepts it.

Client state is scoped to the server connection and rejects stale revisions.
Same-connection world replacement or respawn retains the policy; a genuine
connection change clears it, including when a new server sends its disabled
profile before the cleanup tick.
A profile change queues a native Iris reload when the supported pack is active
and no loading overlay is present. Ordinary Iris reloads clear compilation
evidence but retain the received policy. Disconnect clears that policy and
queues restoration of native rendering. No Iris config, shader-option queue or
pack file is written for the override. A submitted update or queued reload is
not the same as a ready, successfully adapted shader.

The supported rendering target for this candidate is
`ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5` (directory or ZIP), with
verified Iris `1.8.14-beta.1+mc1.21.1`. Other Iris versions do not activate this
adapter or its native celestial/shadow hooks.
The adapter applies the shared rotation/clock in memory to supported Overworld
and Nether lighting programs. It requires the expected source anchors, adapts
the portal helper's source clock too, and reports failed adaptation instead of
claiming a partially adapted pack is ready. Receiving Nether sunlight remains
limited to the existing portal aperture lighting path. End programs, unknown
packs and changed signatures do not gain automatic compatibility. There is no
universal shader support promise. The actual-pack tests cover its procedural
sky path. There is no separate angle override for vanilla/textured sun quads,
so their visual position is not covered by that agreement claim.

With shaders off, server gameplay still uses the enabled shared profile; no
matching shader-rendered sun or shadow is claimed for the native renderer.
Unsupported shader packs retain their own visuals while server gameplay follows
the world profile. Client diagnostics distinguish `shaders_off_server_gameplay_only`,
`unsupported_iris_version`, `unsupported_shader_pack`, `adapter_failed`, `reload_pending`,
`shared_shader_profile_active` and `native_shader_profile`. These are candidate
contracts backed by the local build/tests, still requiring live acceptance.

## Server mob exposure

When enabled, the hook replaces the ordinary mob sun-burn decision's light
visibility term. It samples the mob's eyes against the shared Overworld solar
trajectory and weather. Moonlight is not burning sunlight. Rain and thunder
reduce direct solar strength. The existing random ignition gate, wet/powder-snow
checks and the caller's helmet/fire-immunity behavior remain in place. This does
not make every entity burn or change stored block/sky light and mob-spawn rules.

Exposure can use a clear ordinary Overworld ray or one portal hop to the
Overworld. The ray must pass through the real rectangular opening, with clear
receiver-side and source-side segments. A receiving observer's camera direction
or whether the portal was rendered has no role in this server query.

The first implementation admits only public, visible, non-global rectangular
portals with unit scale, zero thickness, positive width/height no greater than
30 blocks, and a path from the sample to the aperture of at most 32 blocks.
Mirrors, fuse views and player-specific portals are excluded. Here "visible"
is the portal entity's visibility setting, not an on-screen/frustum requirement.
It does not trace a chain of portals or import sunlight from arbitrary dimensions.

Geometry checks read only already-loaded chunks. Each exposure query allows at
most 4096 cell reads and 32 potential nearby portal candidates, counted before
eligibility filtering; all queries share a cap of
131072 cell reads per server tick. Unknown/unloaded geometry and exhausted
budgets cannot invent a clear sunlight path. The reader caches chunks only for
the current query, so a removed or placed blocker is not hidden by a long-lived
exposure cache. The calculation does not request chunk loading or rebuild meshes. A retained
client shadow cache can therefore show sunlight while missing server chunks
cause gameplay exposure to be withheld. The server does not trust stale client
observations or load unknown geometry to manufacture exposure.

The current voxel approximation treats light-blocking values of at least 15 as
opaque. It does not promise exact cutout/partial-shape shadows, shader penumbra,
Sable/contraption geometry or pixel-perfect agreement with every rendered face.
Budget exhaustion can conservatively withhold exposure. These are explicit
limits, not evidence that all supported-looking geometry has been live tested.

## Required verification

Verify one accepted profile survives save/reload and arrives for a new client.
Compare supported rendering with the published rotation/clock, then repeat
server exposure checks with shaders on and off. At fixed day time, test ordinary
sun/shade and a portal's open/blocked/reopened path without depending on camera
visibility. Keep rain, helmets, wetness and random ignition timing separate from
whether a point has direct sunlight. Probe output alone does not prove a mob
burned; a lit screenshot alone does not prove server exposure.

Tests should restore the prior profile and every temporary entity/block, preserve
owner settings, and record limits or unavailable geometry honestly. The separate
DH transition and portal-audio milestones retain their own acceptance gates.
