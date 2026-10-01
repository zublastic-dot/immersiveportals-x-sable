# Portal boundary lighting trial (.35)

Status: experimental ordinary-terrain adapter, not general global illumination.
The owner rejected .33's whole-room color reset after opening a single wall block.
Version .34 replaces that sealed-room requirement with local visibility and
bounded light transport. The .34 opening/reseal and near/far checks passed in
Portal Lab; .35 addresses the subsequently reproduced held-glowstone color flash. Shader-pack
transport and per-emitter RGB are still unimplemented requirements.

## Trigger and behavior

An opaque room in the Nether can have block light zero everywhere and still
look warm and bright: Minecraft's dimension ambient floor enters its lightmap.
The owner's Overworld replica demonstrates the difference. The separate Sable
Dynamic Lights server-leak fix and nonemissive, opaque portal placeholders remain.

The earlier .32/.33 flood accepted only a completely enclosed air component.
A single hole could reach an exterior work limit and remove the entire field.
That behavior is superseded: it was an implementation limitation, not realistic
light entering the hole.

The current client field is bounded relative to a loaded, axis-aligned,
unit-scale rectangular portal. Sky/block levels sampled at the transformed
opening propagate through connected known air, losing one level per step.
Solid cells stop propagation. A fixed domain bounds computation for open terrain;
reaching an edge or an unknown voxel does not invalidate the remaining field.

For each reachable air cell, 50 deterministic, cubically symmetric voxel rays
estimate local exposure. Rays stop at opaque cells, reach the portal aperture,
or escape into the local environment/unknown boundary. The relative visibility
of those openings weights the ambient replacement independently at each cell.
A small hole therefore affects nearby exposure without turning correction off
throughout the room. Larger visible openings increase the local contribution.
Resealing restores the geometry-dependent result, without needing a saved room
classification. Unknown cells retain native exposure locally and are never
invented as known air.

This is a coarse visibility and lightmap-space approximation. It does not trace
multiple diffuse bounces, model material reflectance, or integrate HDR radiance.
Finite directional sampling and block resolution can still produce approximation
artifacts. Those limits must not be described as physically exact lighting.

The actual per-dimension lightmap pixels provide time/weather/gamma/status-effect
color. The renderer removes a weighted portion of the native zero-light floor,
preserves the rendered contribution of local sources, and adds the imported
lightmap contribution. The smaller zero-light floor is retained; the Nether
ambient floor does not become an emitter in the reverse direction.

Version .35 caches an additive ambient offset instead of dividing by a sampled
local brightness. The shaders apply that offset to the current mesh's own light
sample and unlit material color. A newly selected held light therefore keeps its
RGB contribution even before the slower portal field refresh. This also preserves
Colorful Lighting's per-vertex sample and leaves terrain outside the field
unchanged. No field rebuild is requested just because the selected item changed.
Negative resulting illumination is clamped to zero; there is no hue multiplier.

## Renderers and state

Sodium's ordinary terrain shader and DH's ordinary terrain shader sample the
same client-only field. Exact air-cell membership precedes interpolation, so an
exterior roof face cannot borrow the interior cell's correction. Regions are
relative to the current view camera, including portal views. Colorful Lighting
2.5.1's substituted Sodium vertex shader retains its own colored-light sampling
and is included in local GPU link tests.

No world light arrays, dimension types, mod lineup, shader-pack files or DH
databases are changed. This code does not request chunk loads or server relights.

## Bounds and remaining limitations

- Each field covers up to 16 blocks inward from the aperture and up to four
  lateral padding blocks, capped at 32 cells along each axis. Finite support fades
  at exposed domain edges. Reachable output is at most 16 x 32 x 32 air cells;
  one additional sampled boundary layer is used for visibility.
- At most one of 16 active aperture mappings is examined per five client ticks.
  At most four regions are bound for a rendered dimension.
- Either loaded endpoint maintains both directions. Measured geometry and light
  samples survive destination chunk/entity unloads, while current lightmaps
  continue to update. Known samples always replace cached ones, including opened
  or resealed walls. Missing samples affect only their local transport/visibility.
- Moved, retargeted or removed mappings and unloaded dimensions discard snapshots.
  The cache is session-local; a cold distant view without measured geometry
  cannot invent it. Unobserved edits to unloaded chunks wait for refreshed data.
- Only air propagates in this adapter. Glass, partial blocks and fluids are
  conservative boundaries. Scaled, nonrectangular or non-axis-aligned portals
  retain the existing rendering.
- Terrain surfaces are supported; entities, particles, fog and sky are not.
  Coarse LOD geometry outside measured air retains ordinary lighting.
- Overlapping fields use the strongest per-channel correction, not an energy sum.
- Real block emitters retain the game's local lightmap contribution. Per-emitter
  RGB transfer from Colorful Lighting remains unimplemented.
- Iris shader packs are excluded. Their custom ambient, HDR and voxel-GI models
  need verified integration; this adapter does not meet the arbitrary-pack
  requirement by multiplying unrelated final pixels.

Set JVM property `-Dimm_ptl.disablePortalLightTransport=true` to disable the trial,
or restore the previous JAR. No saved-world change is needed for rollback.

## Verification

Regression cases cover the owner's back-wall hole, a roof opening, larger
openings, a second opaque layer, resealing, unknown samples, an opaque partition,
bounded open terrain, opposite directions, large/negative coordinates, cached
open-room geometry, local lamps and ambient mixing. Hidden-context GPU tests
check terrain shader linking, occupancy, exterior exclusion, atlas isolation and
dynamic-light changes while the ambient atlas remains unchanged.

Required live checks remain: the owner's one-block hole and reseal, night/day,
roof source on/off, local lamps, portal crossing and the near/far DH transition.
Build/CI, installation and in-game acceptance are separate evidence.
