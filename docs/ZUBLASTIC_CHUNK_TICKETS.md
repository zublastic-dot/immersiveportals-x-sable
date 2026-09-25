# Chunk-ticket repair, 2026-09-25

This fork starts at GaMiR9195/immersiveportals-x-sable commit
`f5e0471d59407a99c4abd0f084d5a4f2e236f2b8` (branch `0.5.0`). The owner's
installed client and server artifact was
`immersive-portals-sable-compat-0.5.0+ip-6.0.7.jar`, SHA-256
`e9b47bd1e456fb269d51879270eda7c3b622243c6116558e62fb860a86390d69`.
Its chunk-ticket bytecode matches the affected upstream methods.

## Evidence and scope

The captured Kinetic log recorded repeated `Chunk loading failure` messages
with an unfilled third placeholder. The original logger supplied no failure
reason. Those messages alone cannot establish which chunks failed generation.

The code prematurely releases a loading slot when the visible holder is absent
or returns Minecraft's `UNLOADED_LEVEL_CHUNK` sentinel. The installed C2ME
rewrite maps its not-loaded state to that exact sentinel. Polling an entity-
ticking future for a block-ticking ticket also asks for an unrequested stage.
Separately, dimension cleanup passes a ticket level to `removeRegionTicket`,
whose argument is a radius (`33 - level`). That cannot remove the original
ticket. Cleanup must use the radius actually admitted, even if settings change.

The repair retains the existing four-slot loading limit, nearest-first queues,
mod IDs, dependencies and Sable integration. Genuine failed results and
exceptional futures must keep their error details. Prolonged pending requests
must remain observable rather than silently occupying all slots forever.

Tests and live acceptance are separate: passing a simulated scheduling test
does not prove all server errors or rendering defects are fixed. Exact build
and deployment evidence belongs in canonical context history and the task's
local artifact report.

## Building

Use Java 21, `./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G test build`.
Do not silently lose upstream native resources when repackaging. An owner
trial may preserve the exact unchanged native payload from the installed JAR;
record its hash and provenance with the resulting build. No new native code
is part of this repair.

Pass `-PiplNativeResource=/path/to/sable_rapier_x86_64_windows.dll` to package an
explicitly supplied upstream native payload. The original root-file fallback
is retained for upstream developers. A plain CI build without that file is
not the complete owner release and must not be deployed as one.

## Verification

Fourteen deterministic tests exercise the production scheduler through its
ticket-access boundary and the real `ChunkResult`/`CompletableFuture` types.
They cover unpublished holders, an unloaded sentinel followed by a replaced
future, completion, real failures, exceptional/cancelled futures, rate-limited
stall reports, priority changes, disabled admission, and cleanup across a
loading-mode change. They simulate C2ME's documented-by-bytecode sentinel
behavior; they are not an end-to-end C2ME server test.

The operator-only `imm_ptl_chunk_tickets` command is read-only. `waiting` must
remain at or below four per dimension; `completed` counts successful requested
loading stages, not delivery to a client. During a live trial, record advancing
completion counts and inspect both failure and stall counts. A quiet log with
stalled queues is not acceptance. No CPS improvement is established by these
unit tests.

## Packet-decoding investigation, 2026-09-25

Later exploration exposed five distinct chunk coordinates with client packet
decode errors, after the initial join test passed. The two owner-reported map
holes were `[-4474, 717]` and `[-4484, 723]` in the Overworld. Read-only server
region copies contain full chunks with valid saved block palettes at both
positions. A completed server loading ticket does not establish successful
packet serialization, client decoding, or rendering.

Version `0.5.1-zublastic.2` adds evidence capture; it is **not a proven repair**
for those packet failures. The original exception, log, chat message and failure
behavior are preserved. The healthy client path only remembers the initial
buffer index. On failure, a report stores the original section bytes using
absolute buffer access, metadata, and a SHA-256 hash. It does not copy entity
NBT, regenerate terrain, retry decoding, or substitute air.

Reports go under `logs/imm_ptl_chunk_packets/`. The persistent directory is
limited to 16 reports, each at most 2 MiB of section data. Incomplete reports
also occupy the quota; restarts do not reset it. A client attempts capture once
per dimension/chunk, at most 16 times per process. Existing reports are never
overwritten or removed automatically. Preserve useful reports before manually
clearing that diagnostic directory for another bounded investigation.

An operator can run:

```
execute in minecraft:overworld run imm_ptl_chunk_packet -4474 717
```

This snapshots a **currently loaded** chunk's fresh packet serialization. It
does not load a chunk, issue a ticket, generate terrain, or send a packet to a
player. Its metadata explicitly distinguishes this snapshot from a failed
client packet: equality or inequality must be interpreted with capture time
and intervening block changes in mind. A fresh server serialization is not
proof of the exact bytes previously transmitted. The responsible mod remains
unresolved until packet evidence or a controlled reproduction isolates it.

Tests cover recovery of all original bytes after a partial read without moving
the live buffer indices, SHA-256 identity, oversized/invalid captures, persistent
quota behavior and incomplete reports. These tests validate the evidence store;
they do not reproduce the owner's multi-mod decoding defect. Source, build,
installation and runtime acceptance are recorded separately in canonical history.
