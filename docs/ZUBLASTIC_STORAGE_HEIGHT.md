# Hosted plot storage height

## Status and requirement

This describes source candidate `0.5.1-zublastic.71+ip-6.0.7`. The local
NeoForge 21.1.256 / Sable 2.0.6 build passed 669 tests, with 202 skipped;
both native bundles passed verification. Common position and packet guards are
included. The fixed accessor preserves ScalableLux's lighting-owner capability
while its height calculations remain immutable.

These results do not establish successful Mixin startup, deployment, or live
acceptance. The runtime remains `.70` at this source checkpoint. Exact artifact
hashes, CI and later runtime acceptance belong to their separate receipts.

The owner's requirement is support for any native Minecraft 1.21.1 legal
dimension height range, without clipping a ship to the default Overworld or to
the parent dimension currently serving its terrain interactions. The hosting
dimension uses the full legal storage envelope:

| Property | Value |
| --- | --- |
| Minimum block Y, inclusive | `-2032` |
| Maximum block Y, exclusive | `2032` |
| Height | `4064` blocks |
| Stored sections | `254`, section Y `-127` through `126` |
| Light sections including padding | `256`, section Y `-128` through `127` |

This envelope covers native profiles within those bounds, with section-aligned
minimum and height. It does not extend Minecraft's coordinate representation or
make an out-of-range custom dimension legal. Parent terrain retains its own
profile, which can be shorter or have a different minimum.

## Storage and terrain are separate coordinate contracts

`IplChunkStorageHeight` resolves a hosting world's storage accessor from its
immutable `DimensionType`. A hosted chunk must retain that accessor for its
entire lifetime. Its section array length and section-index origin must never
follow a temporary parent-world context. Sable preallocation, chunk-holder
change arrays, plot persistence, and light section indexing must use the same
profile. A mismatched supplied section array is rejected rather than replaced
with empty sections.

The hosting `ServerLevel` still exposes contextual parent bounds for operations
on parent terrain. This is necessary for interactions in dimensions such as the
Nether or a dimension with changed terrain height. Scalar world bounds therefore
cannot be used to index a hosted chunk. Chunk reads use the returned chunk's own
accessor, including optimized Lithium and Sable accelerator paths.

The common position guard resolves a live hosted plot by its X/Z ownership and
uses that plot world's storage bounds for a `BlockPos`. Ordinary terrain and
positions without a live hosted owner keep their normal bounds. It must work on
both client and server without loading chunks merely to answer the height check.
Packet paths likewise must agree on the actual chunk owner's section count and
origin. These guards need integrated startup and packet verification; source
presence or a fixture test alone does not establish acceptance.

## Legacy Sable save migration

Sable's plot save stores sections by array index. An untagged hosting save from
this fork has the legacy profile `minY=-64`, `height=384` (24 sections). Loading
those keys directly into the new array would move their absolute Y coordinates.

`IplPlotStorageMigration` validates the entire plot before Sable begins its
incremental load, then works on a copy of the saved tag. It translates section
keys using:

```text
newIndex = oldIndex + oldMinSection - newMinSection
```

For the legacy profile, the offset is `123`; a block at Y `208` remains at Y
`208`. Section payloads, absolute block-entity positions, scheduled block/fluid
tick positions, and other copied metadata retain their values. The migration
does not shift a ship's pose or rewrite unrelated user data. Heightmaps are
removed only when the storage profile changes, because their encoded offsets
and bit width depend on that profile; Sable rebuilds missing heightmaps from the
restored sections.

New saves carry `ipl_sable:storage_profile` with version, minimum Y, and height.
Tagged profiles are validated explicitly. Invalid profiles, ambiguous section
keys, sections outside their declared range, or incompatible position entries
fail before loading rather than being silently dropped. The legacy assumption
applies to this fork's untagged hosting saves, not arbitrary third-party save
layouts. It cannot reconstruct data already lost before this candidate.

## Transfers must not clip the original

Rehome and legacy transit preflight every loaded source plot chunk against the
destination's fixed storage profile before allocating a twin. The copy routine
repeats that check before placement and uses a fixed source-chunk list. Any
non-air cell outside the destination range or inconsistent section-array
metadata rejects the transfer. There is no out-of-range skip-and-delete path.

Destination chunk allocation, block placement, or block-entity restoration
failure aborts the copy. The caller keeps the original and attempts to remove
the destination twin; rollback failures remain explicit errors. This is a
bounded loss-prevention change, not a guarantee that every third-party observer
side effect is transactionally reversible. Existing neighbour-notification and
ticker behavior remains subject to runtime checks.

## Allocation and packet cost

Uniform storage allocates 254 section slots instead of the former 24: 230 extra
slots, about 10.6 times the section count per hosted chunk. Empty palettes avoid
allocating a dense block-state array for every empty section, but section
objects, associated arrays, scans, and packet serialization still have a cost.
Light masks address 256 padded sections instead of 26. Actual heap use, packet
sizes, and timing depend on the ship and active lighting implementation and
must be measured rather than inferred from the slot ratio.

Vanilla 1.21.1 `ClientboundLevelChunkPacketData` rejects a serialized chunk
section buffer larger than **2,097,152 bytes (2 MiB)**. This is the section-buffer
limit, not a limit on the entire packet including heightmaps and block-entity
metadata. The candidate does not raise or bypass it. Empty-section overhead and
large, diverse palettes across many occupied sections need packet-size testing;
legal vertical coordinates do not by themselves prove that every possible
chunk payload fits that existing limit. Oversized data must never be made to
fit by discarding sections or replacing blocks with air.

## Runtime acceptance matrix

All rows below remain acceptance work until exact candidate artifacts and
observed results are recorded separately. Unit fixtures and bytecode contracts
support these checks but do not replace a real Mixin launch.

| Case | Required evidence |
| --- | --- |
| Client and server startup with the active mod stack | Every required hook applies, including constructor hooks; no Mixin failure or silently missing height route. |
| Normal assembler and ignition at varied raw plot Y | Real glue/assembler interaction followed by a real ignition click; block, block-entity, and portal observations agree across client/server. Include raw Y below `-64`, above `319`, and near both storage endpoints with room for the complete fixture. |
| Different visible world altitudes | Confirm pose mapping separately from raw plot storage Y. A ship displayed high in the world is not evidence of high raw section storage. |
| Non-vanilla parent profiles | Exercise differing minima and maxima, including a changed Overworld profile, Nether-like bounds, and a shorter profile sharing a minimum. Parent terrain reads and hosted plot reads must both remain correct. |
| Save, unload, and restart | Restore an untagged legacy plot and a newly tagged plot; compare absolute block Y, palettes, block entities, ticks, pose, and user data before and after. Repeat a tagged reload to check stable indexing. |
| Rejected transfer | An incompatible destination preserves the complete source and leaves no usable partial twin; inspect any rollback error explicitly. |
| Chunk and light packets | Join, retrack, update blocks, and reload plots across the full storage range; verify section counts, origins, padded light masks, payload size, and absence of decode failures. |
| ScalableLux and the active lighting stack | Validate startup, light updates, chunk load/unload, and reload with the actual installed implementation; check both parent terrain and hosted chunks. |
| Resource cost | Record representative hosted-chunk allocation, packet sizes, and copy/load timing, including sparse and vertically extensive plots. |

Runtime evidence must identify the exact built and installed hashes. A clean
log without successful assembly, ignition, persistence, and packet observations
is not full-height acceptance.
