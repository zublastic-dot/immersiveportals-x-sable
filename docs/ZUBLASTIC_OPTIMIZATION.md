# Adaptive hosting storage and focused runtime optimization

Candidate: `0.5.1-zublastic.73+ip-6.0.7`, Minecraft 1.21.1,
NeoForge 21.1.256, Sable 2.0.6. This source checkpoint does not claim deployment
or measured FPS/TPS improvement.

## Changes

- Select one immutable hosting storage range from resolved parent dimensions,
  saved data and the previous adaptive profile before allocating levels. The
  intended Lab range is -96 through 511: 38 sections instead of .72's 254.
  See [storage and migration contracts](ZUBLASTIC_STORAGE_HEIGHT.md).
- Resolve client/server portal anchors through Sable's existing UUID index.
  Preserve world search order and type/removal checks; introduce no extra cache
  that could become stale after rehome.
- Check whether tracing is enabled before building route diagnostic varargs.
- Stamp newly owned plot-save NBT in place; retain the copy-preserving API for
  callers that do not exclusively own their input. This removes a redundant
  deep copy without sharing mutable saved data with the live chunk.

## Measurements

`IplAdaptiveStorageMeasurementTest` is opt-in with
`IPLSABLE_HEIGHT_AUDIT_SNAPSHOT` pointing to a read-only hosting save snapshot.
It hashes input before/after scanning, runs three warm-cache inventory rounds,
and uses real Minecraft `LevelChunkSection` construction and serialization.
Thread allocation is measured with the JVM's thread allocation counter.

Local measurement on 2026-10-10, Java 21, empty sections with a single vanilla
biome palette:

| Measured operation per synthetic chunk | 38 sections | 254 sections |
| --- | ---: | ---: |
| Section construction allocated bytes | 22,360 | 149,368 |
| Serialized section payload bytes | 456 | 3,048 |
| Serialization allocated bytes | 536 | 3,128 |

The first two measurements decrease by about 85%. They exclude the rest of the
chunk, lighting storage, populated palettes, packet metadata, compression and
world simulation. They are not whole-game memory, network or FPS claims.

The anchor lookup fixture uses production compiled consumers and checks index
updates/removal. For 2,000 lookups across three worlds, indexed probes stay at
6,000 for 1, 64 and 4,096 ships per world; the corresponding previous iteration
counts are 6,000, 384,000 and 24,576,000. Fixture timing is not a live-frame result.

The diagnostic save copy contains 26 files (6,785,248 bytes). It was copied from
a running Lab server, and some reads changed while being copied, so it is useful
format evidence but not a consistent authoritative migration snapshot. The
actual startup must scan the stopped world's saved files again. No world backup
or production-world reset is part of this measurement.

The copied data's occupied bounds are -16..223; union with the configured
parents selects -96..511. Projecting the air-section archive across all 1,545
copied terrain records retains 333,720 section payloads in 1,161,496 compressed
bytes, at most 752 bytes per record in this run. Each loaded chunk retains only
immutable compressed bytes; promotion shares them and saving decodes fresh NBT.
These are exact codec bytes for this diagnostic dataset, not measured concurrent
heap use or proof that every projected archive was saved by a running .73 server.

## Required runtime acceptance

Record exact source/JAR/native hashes and both sides' selected range. Check
retained ships and portals, normal glue/assembler/flint ignition, portal
traversal, packet/light updates, save/restart and archive persistence. Restore
the user's newly captured position and settings. Keep native envelope support;
never obtain a smaller allocation by discarding occupied cells or pending work.

Hosting eligibility/delivery is managed by ModAutomator. This runtime policy
applies to the validated Sable/IP stack and does not install those mods into
unrelated customer packs or change ordinary world-generation height.
