# Native JNI smoke test

Run with Java 21 and Python 3.11 or newer, using a native built for the host OS:

```sh
python3 natives/tests/run_smoke.py \
  --native /absolute/path/sable_rapier_x86_64_linux.so \
  --sable-jar /absolute/path/sable-neoforge-1.21.1-2.0.6.jar \
  --output /absolute/path/smoke-results
```

`--compile-only` validates Java compilation and descriptors without loading a library.
It is not a passing native smoke run. Output includes exact input hashes, descriptor
comparisons, the JVM/native log and a JSON acceptance report. The JVM uses `-Xcheck:jni`.

The Java files here are **test-only declarations under the exact production JNI class
names**, not Sable's production Java implementation. They avoid Sable's static game
loader and Minecraft dependencies. Before loading the native, the runner checks every
declared Sable method descriptor against classes from the supplied mod, searching its
nested JARs as needed. It also checks the collision callback descriptor and the Atlas
extension declarations against the current production source.

The smoke test requires real native behavior:

- Three scenes share one world. A voxel body in the middle scene advances by exactly
  velocity × timestep when the final scene steps, then advances identically through a
  different scene. This catches the Linux stock-scene/fused-step mismatch.
- Five-argument `newVoxelCollider` registration succeeds, including a Java callback.
- Rope creation/query/removal exercises the corrected `long` return ABI. Zero is a
  deterministic unused return, not a newly added public success protocol.
- After all scenes are disposed, a fresh world uses a different gravity. A hosted body's
  identity image lands on parent-chart terrain and calls the Java collision callback.
  A control body with no image falls through that foreign terrain, proving chart isolation.
- Bodies, images, terrain and scene handles are explicitly removed in `finally` blocks.

Passing this test does **not** establish Minecraft mixin startup, the Java fused-step
scheduler, real staff input, portal ignition, arbitrary chart counts, rotated portals,
or server gameplay acceptance. Those require their own tests and live validation.
