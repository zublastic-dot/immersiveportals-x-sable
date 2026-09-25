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
