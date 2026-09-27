# Portal surface outline preference

The owner requested an in-game choice: some players want the block selection
outline on the portal surface, while others find it distracting. Disabling the
shader pack's global selection outline also hides ordinary block outlines.

## Setting

Open **Mods > Immersive Portals x Sable > Config > Client Config** (the
`immersive_portals_core` entry), or run
`/imm_ptl_client_debug config` and choose **Client Config**. Set **Show Portal
Surface Outline**, then save. The setting applies without restarting and is
stored locally as `showPortalSurfaceOutline` in `config/immersive_portals.json`.
It defaults to `true`, including when an older config lacks the field.

- Off suppresses only selection-outline drawing for
  `immersive_portals:nether_portal_block`, the invisible portal surface block.
- On preserves the existing outline behavior. It does not force an outline if
  the shader, another mod, or normal portal targeting already suppresses it.
- Obsidian frames, other blocks, hit results, portal interaction, collision and
  teleportation keep their existing behavior. This is a local client preference.

## Implementation and validation boundary

The client-only `MixinPortalSurfaceOutline` cancels `LevelRenderer.renderHitOutline`
at entry only when the option is off and the supplied `BlockState` is the portal
placeholder. It uses the renderer's resolved state rather than another lookup
through a source player's position or a potentially different render dimension.
This also avoids confusing Sable plot coordinates with world coordinates.
Sable 2.0.3 transforms the outline pose around NeoForge's highlight call, then
uses this same outline method; its pose cleanup is left intact.

The existing AutoConfig/Cloth screen handles persistence and save/cancel.
NeoForge's config-screen factory is registered only from client initialization;
the common entrypoint receives its own injected `ModContainer`. No Iris/DH fork,
shader edit, new dependency or server setting is involved. Build and source
contract checks do not replace a live toggle check in Portal Lab.

Live check: with a portal surface targeted, toggle off/save, verify the rectangle
disappears while the obsidian frame and ordinary blocks still have outlines;
toggle on/save and verify prior behavior returns. Repeat with shaders disabled
and on a moved Sable frame, then reopen the menu/relaunch to check persistence.

The native-preserving Java 21 build and all 94 existing regression tests pass,
including seven DH GPU checks. The client-only mixin target/signature was checked
against the installed 1.21.1 renderer and Sable 2.0.3's outline wrapper. No new
test duplicates this small drawing preference; UI and visual behavior still need
the live check above. Candidate .13 SHA-256:
`e95c6ea56c3c64da2c99b6717349f53a951340559eade1f954bc26243acb7a60`.
The prior .12 fog repair and every existing render/moving-frame class are
byte-identical; only the config and client entrypoint classes changed, plus the
new outline mixin. The physics native is unchanged.
