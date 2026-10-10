# Bounded hosted-frame ignition trace

Build `.69` adds observation, not an ignition repair. Tracing is off until an
operator explicitly arms it. The existing interaction, reach, permissions,
item-event and portal-generation decisions keep their original outcomes.

Use the server command (operator permission level 2):

```
/ipl_ignite_trace start <player> <x> <y> <z> <radius> <attempts> <seconds>
/ipl_ignite_trace status
/ipl_ignite_trace stop
```

The target is the clicked **block coordinate**, including Sable plot coordinates
for a hosted body. The dimension and UUID come from the selected player's current
server state. Radius is a box half-extent, 0–32 blocks; attempts are 1–8; lifetime
is 1–120 seconds. Each attempt emits at most 64 events. Only one player can be
armed at once. A new arming stops the previous server and client windows.
The server sends a fixed arming message through IP's existing connection to that
player. Confirm the matching `armed` token in **both** logs before clicking;
the command's success only proves server arming and message submission.

Output is tagged `[IPL-IGNITION-TRACE]` in the ordinary client and server logs.
Shared token, side, attempt number and packet sequence correlate the records.
Attempt numbers are local to each side: a suppressed client click cannot consume
a server attempt. No payload bytes, chat contents, inventories or entity NBT are
dumped. Log field lengths, events, attempts, target scope and lifetime are bounded.
Expiry, disconnect/server stop and explicit stop prevent further capture.

The client records the actual use-key entry, NeoForge click cancellation, item
handling and final send. Send completion is not proof of server receipt. The
server observes packet/redirect entry, actual reach and interaction decisions,
item handling and flint branches. Diagnostics must not repeat routing lookups:
Sable plot resolution can arm world-frame context as a side effect.

For acceptance compare an ordinary frame and an already assembled frame at
matched physical reach, with the same hand/item and sneaking off. A disposable
block placement on a spare face of the hosted body can help separate common
interaction routing from the flint path. Confirm outcomes from server state;
client prediction alone is insufficient. Failed placement alone is inconclusive
because placement has additional checks. Do not alter the real frame to make a
test pass. Preserve test baselines and remove only identified temporary objects.

The `.68` trace-free evidence established no generation attempt for the failed
hosted ignition. It did not prove whether the interaction packet was received.
The new trace is intended to resolve that boundary; deployment, hook acceptance
and any diagnosed cause require separate live evidence.

## Block-storage comparison (.70)

The `.69` dedicated-server capture accepted the hosted click and resolved its
owner, but the actual flint target read returned air. The exact storage lookup
responsible was not established. `.70` adds observations of the chunk selected by
the original block read, its owning dimension and height profile, and the raw
section state. These observations are restricted to the clicked coordinate and
eight distinct stages within the existing 64-event attempt budget. Optional
hooks may be displaced by another mod; an absent stage does not prove a bypass.
Repeated plot-resolution logs are deduplicated and likewise cannot prove absence
of subsequent lookups.

An operator can separately run:

```
/ipl_ignite_trace probe <x> <y> <z>
```

This probes one live hosted plot owned by the command source dimension. It
compares loaded-only parent chunk-source, hosting chunk-source and direct plot
reads, recording whether each is the same chunk. It neither generates chunks
nor invokes the creating `Level.getBlockState` path; the real click trace covers
that path separately. Output uses `[IPL-BLOCK-PROBE]`. The previous world-frame
context is restored after the command. This diagnostic is not an ignition fix.
