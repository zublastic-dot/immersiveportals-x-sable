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
Non-LevelChunk providers, including ProtoChunks, retain their existing path.
The two external mixins are client-only `@Pseudo` targets; ScalableLux remains
optional and its implementation is not bundled.

## Verification scope

The focused suite contains 16 tests: the exact 38/24 negative control, shifted
origins with equal section counts, shorter parents, identical keys belonging to
different world instances, crossing/owner stability, null and non-LevelChunk
providers, and bytecode contracts binding the four actual guards.

Plain JUnit does not apply runtime mixins. A successful build and these tests
do not by themselves prove a live Physics Assembler run. Runtime acceptance
must identify the installed artifact, client versions and observed assembly.

This is a client assembly-crash correction, not a claim of universal server
light-engine isolation. `ServerChunkCache` overrides the inherited lighting
getter, and SableScalableLux has a separate server plot-engine path.
