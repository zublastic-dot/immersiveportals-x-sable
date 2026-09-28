# Portal Lab log repairs (.16 / .17)

Scope: IP/Sable only, following the owner-requested .15 log audit. The accepted
carried-portal fix, native DLL and installed mod lineup are retained.

## Evidence and fixes

- **AsyncParticles sound:** .15 `latest.log:3479` records a worker entering
  `CrossPortalSound.createCrossPortalSound` through `ClientLevel.playSound` and
  accessing entity storage off-thread. Cancel the worker's call and replay its
  original arguments on the client thread. Check world identity on delivery so
  disconnected/replaced worlds cannot play stale sounds. Client-thread calls
  retain their synchronous path. No AsyncParticles setting is changed.
- **Simulated diagram shader:** the installed Simulated 1.3.2
  `contraption_diagram/outline_diagram.vsh` generates a screen triangle from
  `gl_VertexID`; it has no `Position` or `ModelViewMat`. Remove this fullscreen
  effect from both world-clipping lists. Keep clipping for world geometry.
  Match transformations by shader stage as well as name, avoiding false
  fragment-stage no-change warnings for vertex-only rules. Genuine failures
  still warn.
- **Veil OpenGL object lifecycle:** the .15 debug log records `glObjectLabel`
  on non-created vanilla buffers/VAOs and `glNamedBufferData` on Veil buffers
  returned by `GlStateManager._glGenBuffers`. A generated name is not yet an
  object. Instantiate freshly generated names at that allocation seam with
  bind/restore; this works without requiring DSA. Do not mutate arbitrary IDs at
  label time, consume errors, or turn off debug reporting. Real-GPU regressions
  reproduce both original driver errors and verify the corrected operations,
  binding preservation and preservation of unrelated errors.
  The .16 live startup exposed a missed path: IP's own `cacheGlBuffer` HEAD
  injections return batch-reserved names before the original return instruction.
  A return-value injector therefore never initialized these names. In .17,
  wrap the complete allocator so cached early returns receive the same lifecycle
  handling. The cache stays enabled; unused reserved names are not instantiated.
- **Assembly movement race:** .15 records three missing-sublevel movement
  errors at initial assembly, before hosted full sync. Sable 2.0.5 treats an
  integrated-server local connection as UDP-connected and delivers its fast
  path through a separate client network event loop. The initial allocation
  travels on the ordinary connection. Our previous TCP guard covered only
  cross-dimension viewers, leaving the first parent-dimension tick exposed.
  With the hosting dimension available, keep that tick on the same ordered
  connection as allocation/removal too. Legacy same-dimension mode without
  hosting retains its previous transport. Two-channel regressions exercise
  movement overtaking allocation and arriving after removal. Live assembly
  verification remains required to confirm these were the observed races.

OpenGL semantics: [Khronos object creation documentation](https://wikis.khronos.org/opengl/OpenGL_Object).
The inspected deployed artifacts are Sable 2.0.5, Veil 4.3.2 and Simulated 1.3.2.
Build dependencies retain the existing compatibility baseline; installed bytecode
and the actual shader asset were inspected rather than assuming identical versions.

## Validation and limits

The .16 test/build run passed all 124 tests (zero failed/skipped), including 11
hidden-GPU tests. Its live startup succeeded in 79.886 seconds and the diagram
compile failure did not recur, but all 22 startup GL errors remained (15 buffer
labels, 5 VAO labels and 2 uploads). That is a failed GL repair checkpoint, not
live acceptance. The .17 regression additionally exercises the actual allocator
wrapper with batch-reserved names and checks successful labeling and DSA upload.
All 125 tests/build pass for .17, including 12 hidden-GPU tests, with no skips.
The exact candidate hashes and current validation are recorded with canonical history.
The sound tests exercise worker-to-client dispatch and world teardown; GPU tests
compile both the fullscreen effect and clipped world geometry through the real
Veil preprocessor. No test silently skips a required graphics context when GPU
tests are requested.

The three later latched `OpenGL Error 1281` messages alone did not identify their
origin. Fixing the independently identified Veil driver failures does not prove
that every later GL error has the same cause. Check the new runtime log.

The DH "mixin did not run recently" watchdog during a long pipeline build and
AllTheLeaks retained-world report are not proven fork defects. No watchdog,
leak diagnostic or error logger is muted by this change. Heap retaining paths
would be needed to attribute the latter.
