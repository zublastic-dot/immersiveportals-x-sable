# Portal enclosure lighting trial (.33)

Status: experimental first renderer adapter. The .32 near-room night comparison
was observed live; its distance/unload regression is addressed in .33, pending
live verification. Shader-pack light
transport remains unimplemented. This is not a claim of general ray-traced GI.

## Trigger and behavior

An opaque room in the Nether can have block light zero everywhere and still
look warm and bright: Minecraft's dimension ambient floor enters its lightmap.
The owner demonstrated that difference with a separate Overworld room at night.
The Sable Dynamic Lights server-leak fix and zero-emission opaque portal
placeholders remain necessary, separate fixes.

This trial discovers a bounded air enclosure beside a loaded, axis-aligned,
unit-scale rectangular portal. It samples sky/block light at the transformed
opening and propagates those levels through connected air, losing one level per
step. Full blocks, including the roof, are boundaries. It reads current client
geometry and light; it never writes world light arrays, sends relight requests,
loads chunks, changes a dimension type, or changes server state.

The actual per-dimension lightmap pixels provide time/weather/gamma/status-effect
color. The renderer removes the room's native zero-light floor, preserves the
measured lightmap contribution of local sources, and adds the imported lightmap
contribution. The smaller zero-light floor is retained; the Nether ambient floor
does not become an emitter in the reverse direction. This is an approximation
in lightmap color space, after gamma, rather than a linear HDR radiance solver.

Sodium's ordinary terrain shader and DH's ordinary terrain shader sample the
same client-only 3D field. Exact air-cell membership precedes interpolation, so
the exterior roof cannot borrow an interior correction. Field origins are
relative to the current view camera, including nested portal views. No DH data
or shader-pack files are edited or deleted. Local texture/albedo detail remains.
Colorful Lighting 2.5.1 substitutes a differently named Sodium vertex shader;
that known namespace uses the same geometry adapter while retaining its own
colored-light sampling. The installed shader is included in local GPU link tests.

## Bounds and conservative fallbacks

- At most 4,096 air cells per solve and fewer than 32 cells along each axis.
- At most one of 16 active aperture mappings is examined per five client ticks.
  Either loaded portal endpoint maintains both directions; counterpart entities
  unloading does not discard the field. Moved, retargeted or removed apertures
  and unloaded dimensions discard their snapshots. At most four regions are
  bound for a rendered dimension.
- Verified air/opaque geometry and measured light levels are cached in memory
  for unloaded chunks, while current lightmaps still apply time/weather/gamma.
  Loaded samples always replace cached ones. A known geometry change with a
  partly unloaded boundary invalidates the proof. This cache is session-local:
  a room not yet measured this session cannot receive a guessed correction.
- Reaching unloaded space, the extent limit, or the cell budget does not prove
  an enclosure. Without a previous matching, verified snapshot it disables the
  correction for that candidate. This does not request additional chunks.
- Only air propagates in this first adapter. Glass, partial blocks and fluids
  are conservative boundaries. Scaled, nonrectangular and non-axis-aligned
  apertures retain existing rendering.
- Corrections apply to terrain surfaces, not entities, particles, fog or sky.
  Coarse LOD geometry outside the measured enclosure retains ordinary lighting.
- Multiple recognized apertures combine the strongest correction per channel;
  this does not sum physically independent light-source energy.
- Real block emitters use the current game's lightmap contribution. Per-emitter
  RGB transport from Colorful Lighting is not implemented by this adapter.
- Iris shader packs are explicitly excluded: arbitrary packs can use custom
  ambient terms, deferred HDR buffers or voxel GI. Multiplying their final
  pixels by a vanilla-lightmap ratio would be an unsupported cosmetic shortcut.
  The common field is available for subsequent pack-aware integration.

Set JVM property `-Dimm_ptl.disablePortalLightTransport=true` to disable the trial,
or restore the previous JAR. The default enables the no-shader trial. No persistent
config or world change is needed for rollback.

## Verification

Pure tests cover enclosure discovery, opaque partitions, opening the roof,
unknown boundaries, work limits, multiple incoming levels, light attenuation,
large/negative coordinates, source floor rejection and retained local light.
Hidden-context GPU tests exercise occupancy filtering, roof/exterior exclusion,
adjacent atlas isolation, disabled identity, and compile the actual Sodium/DH
shader pairs. Existing portal tests are retained.

These tests do not prove the owner's in-game comparison. Required live checks:
night/day from outside and inside, roof source present/absent, local lamp on/off,
opening and resealing a wall, portal crossing, near/far DH transition, and normal
Nether outside the room. Source/hash, CI and runtime acceptance are separate.
