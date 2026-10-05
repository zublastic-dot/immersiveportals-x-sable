# Hosted client lighting ownership

## Failure and correction (.64)

The dedicated-server assembly report `crash-2026-10-05_20.08.47-client.txt`
failed in ScalableLux `StarLightEngine.setBlocksForChunkInCache`: section index
24 against a 24-element hosted chunk. The server Overworld spans Y=-96..511
(38 sections), while `ipl_sable:sublevels` spans Y=-64..319 (24 sections).
The first sublevel and its plot chunk had been received immediately before the
client lighting failure. The portal had not been lit.

IP-Sable intentionally exposes hosted plot chunks through parent-world chunk
lookups for rendering and interaction. That does not make those chunks valid
inputs to the parent's light engine. Section arrays and light masks belong to
the chunk's actual Level, including its section origin, not the player's world
or the dimension in which the sublevel is physically located.

The client correction filters foreign LevelChunks at four lighting boundaries:

* Inherited `ChunkSource.getChunkForLighting`, after virtual lookup (also covers
  the `ImmPtlClientChunkMap` override).
* ScalableLux's immediate `StarLightInterface.getAnyChunkNow` lookup, which
  bypasses the ordinary lighting getter.
* ScalableLux's explicit `scalablelux$clientChunkLoad` upload, before it can write
  parent-indexed nibble arrays into the supplied hosted chunk.
* `ClientPacketListener.enableChunkLight`, before indexing sections using the
  packet listener's world.

World **identity** is required. Matching heights or dimension keys are not
sufficient, including after a world reload. Foreign lighting lookups return
null; ScalableLux already handles missing chunks by draining stale work.
The hosting world's own packets and light engine remain responsible for its
lighting. Existing remote-world lighting ticks are unchanged. Parent packet
masks are not forwarded because their vertical indexing belongs to the parent.

This does not resize arrays, swallow lighting exceptions, disable ScalableLux,
or change ordinary chunk lookups. No dimension names are special-cased.
Non-LevelChunk providers retain their existing path; ProtoChunks additionally
must have the position requested by the lighting lookup.
The two external mixins are client-only `@Pseudo` targets; ScalableLux remains
optional and its implementation is not bundled.

## Verification scope

The focused suite contains 16 tests: the exact 38/24 negative control, shifted
origins with equal section counts, shorter parents, identical keys belonging to
different world instances, crossing/owner stability, null and non-LevelChunk
providers, and bytecode contracts binding the four actual guards.

## Vacant plot placeholder correction (.65)

The later `crash-2026-10-05_22.16.21-client.txt` failed in
`StarLightEngine.getEmptinessMap` with index -7682292 against a 25-element
cache. This is a separate horizontal-coordinate failure. The plot rendering
lookup returns a same-world `EmptyLevelChunk` at (0,0) for vacant plot-grid
positions. That sentinel passes .64's owner check. ScalableLux centers its 5x5
cache on the requested coordinate, then uses the returned chunk's own position
when handling section changes. For a request at (1280064,1280448), reading the
sentinel at (0,0) yields exactly `12 - (1280064 + 5 * 1280448) = -7682292`.

All four lighting boundaries now also require the chunk's position to match
the query or packet position and reject `EmptyLevelChunk` missing-data
sentinels. A real loaded chunk whose blocks are all air remains valid, including
at (0,0). No lighting data is rebased, resized, synthesized or forwarded, and
generic rendering/interaction lookups are unchanged. ScalableLux's existing
missing-center path destroys the cache and returns without accessing the
sentinel's position. This correction does not by itself establish the cause or
repair of the separately reported immovable frame and disappearing blocks.

Five additional tests cover coordinate mismatch, the same-world negative
control, the matching-coordinate sentinel, retention of ordinary/hosted chunks,
and the exact reported negative index. The latter executes the installed
ScalableLux `setupEncodeOffset` and `getEmptinessMap` bytecode in a small fixture;
the installed JAR must be present on the test runtime classpath. The fixture
does not claim to apply mixins or reproduce a complete live assembly.

Plain JUnit does not apply runtime mixins. A successful build and these tests
do not by themselves prove a live Physics Assembler run. Runtime acceptance
must identify the installed artifact, client versions and observed assembly.

This is a client assembly-crash correction, not a claim of universal server
light-engine isolation. `ServerChunkCache` overrides the inherited lighting
getter, and SableScalableLux has a separate server plot-engine path.

## Foreign-chunk block access with Lithium

The installed Lithium 0.15.4 `world.inline_block_access.LevelMixin` replaces
`Level.getBlockState` with a lookup that fetches a chunk's section array but
indexes it with the caller world's section origin. The plot bridge deliberately
returns a hosting-world chunk to parent-world block reads. With the reported
parent minimum Y of -96 and hosting minimum Y of -64, a parent read at Y=205
therefore reads the hosted section containing Y=237. This independently explains
an air block result while the actual hosted block and its rendered geometry
remain present. The same read path runs on the dedicated server.

`IplLithiumBlockAccessMixin` wraps that one section-index call, using the already
fetched chunk's index for foreign-world chunks and retaining the original call
for same-world chunks. It leaves the chunk selection, empty-chunk handling,
section bounds checks, fluid reads and all write paths intact. It is common-side,
dimension agnostic, and does not require or bundle Lithium. Its lower priority
allows the default-priority Lithium overwrite to be applied first; with vanilla
block access the optional injection has no target and does nothing.

The regression executes the installed Lithium lookup bytecode with the compiled
production index handler in a small fixture, including the exact 205/237 case,
different dimension height profiles, bounds, empty chunks and the unchanged
same-owner path. Annotation and local-variable contracts bind the fixture to the
runtime injection point. Plain JUnit does not apply the complete Minecraft mixin
stack: live assembly, interactions and motion still need separate verification.
Unexpected extra blocks in a later server save are not established as caused by
this read-only mismatch, and this patch does not modify saved blocks.
