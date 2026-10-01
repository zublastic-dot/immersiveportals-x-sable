# Hosted portal frame recovery

## Reproduction and cause

A conventional dimension change calls `Minecraft.updateLevelInEngines`, which
posts `ClientCleanupEvent` and disposes Immersive Portals' remote client worlds.
That includes the Sable hosting container. The server can still consider those
ships tracked and continue sending bounds/movement/parent metadata instead of
full allocation data. Metadata cannot recreate an absent client sub-level.

Portal Lab .36 reproduced this state: the hosted gather reported zero structures
while parent sync repeatedly reported a missing hosted sub-level. The source
obsidian frame disappeared, while the frame inside the portal destination view
remained. Leaving Sable's tracking range and returning sent a full sync and
restored the entire frame. This diagnosis applies to that reproduced failure;
it does not assert that every missing face has the same cause.

## Recovery protocol

The client remembers its connection identity and coalesces actual cleanup events.
It requests recovery after a subsequent gameplay-ready client tick on the same
connection. Exit, replacement connection and initial login do not replay an old
request. Ordinary portal rendering and unchanged ticks do not request recovery.

The existing authenticated RPC channel supplies the requesting ServerPlayer. The
client supplies only a monotonically increasing cleanup revision, never a target
player, dimension or ship. Server work is confined to the game thread, with one
pending task per connection. A one-second tick cooldown delays the latest request
rather than dropping a second cleanup.

Recovery marks the viewer's currently tracked hosted ships for a single full-sync
replay. Normal tracking removal remains authoritative; only an eligible viewer
receives a replay. Per-ship pending state survives temporarily missing eligibility
and is pruned when the ship, tracking entry or connection disappears. This avoids
an in-flight allocation becoming a client ghost if eligibility changes during
recovery. Full sync retains the existing packet redirection, parent stamp and
straddle-session snapshot order. No saved world state or render settings change.

## Validation

The regression suite covers client lifecycle/connection replacement, coalescing,
server cooldown and delayed revisions, sender isolation and eligible replay.
Build against both supported DH artifacts and retain the unchanged upstream native
resource for the Windows trial. Hosted CI and exact installed hashes must be
recorded separately from source validation.

Runtime acceptance requires repeated conventional dimension switches while the
portal frame remains in range, including rapid switches and range changes. Verify
full-sync replay, visible frame recovery and the absence of continuing missing
hosted-sub-level warnings. Test ordinary portal traversal separately, since it
normally preserves client worlds. Compilation and unit tests do not establish
runtime acceptance. See canonical `history/2026-10-01-portal-frame-visibility.md`
and `M:/PortalAudioCompat-20260929/frame-visibility` for evidence and limitations.
