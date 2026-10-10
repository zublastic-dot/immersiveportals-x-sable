# Native physics on dedicated Linux servers

The hosted-sublevel design requires the fork's Atlas Rapier native backend.
It shares one simulation across dimension charts and supplies image colliders
for terrain contact in a body's gameplay dimension. Stock Sable creates separate
native scenes and cannot be substituted while retaining this Java architecture.

Version `.66` only bundled/loaded the Windows native. On Kinetic's Linux x86_64
runtime it silently loaded stock Sable while the fused driver advanced only the
last dimension's scene. The owner assembled a 32-block frame successfully and
staff packets selected its UUID, but its hosted scene was not advanced. This
explains a concrete frozen-physics defect; it does not prove the separate
portal-ignition failure has the same cause.

The `.67` candidate selects Windows or Linux x86_64 explicitly and extracts the
corresponding resource to a content-addressed cache. Loading stays in the merged
`Rapier3D` class to preserve JNI resolution. A harmless null-scene extension call
must resolve before availability is published. Missing resources, incompatible
platforms, linking failures and `-Dipl.sable.customNatives=false` now stop physics
initialization with an actionable error. Falling back to stock would silently
disable essential simulation and collision behavior; this fork has no coherent
stock-native mode. Native failure occurs during Sable initialization, not a
guarantee that Minecraft has not already opened world files.

The null-scene call checks extension binding, not complete physics behavior.
Platform-native smoke tests must additionally verify shared-scene stepping,
the installed Sable JNI signatures, and the supported collision paths before
deployment. Java unit tests alone do not prove native compatibility.

## Building a complete release

The native workspace pins `nightly-2026-01-29`. The Linux build script uses the
locked dependency graph and stages `libsable_rapier_x86_64_linux.so`. Preserve
the existing tested Windows native unless deliberately rebuilding and testing
it. Both binaries' source/provenance and hashes belong in the release receipt.

Supply both native paths to Gradle:

```
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2G \
  -Psable_version=2.0.6 -Pneo_version=21.1.256 \
  -PiplNativeResource=/path/to/sable_rapier_x86_64_windows.dll \
  -PiplLinuxNativeResource=/path/to/libsable_rapier_x86_64_linux.so \
  test build verifyIplNativeBundle
```

The ordinary Java-only CI build is not a deployable native release.
`verifyIplNativeBundle` checks both packaged resources and platform headers;
native ABI and physics execution remain separate acceptance requirements.

For Sable 2.0.6 the Java `newVoxelCollider` ABI has five arguments. The native
wrapper must not read an undeclared sixth boolean. The removed flag only fed
an otherwise unpopulated dynamic-collider registry; the existing fallback
behavior is retained. `removeRope` must return the Java-declared `long` even
though Sable's current caller discards it. Exact native smoke results and live
acceptance must be recorded before claiming the owner's frame is repaired.

## Candidate validation, 2026-10-06

Local full builds against Sable 2.0.6 with NeoForge 21.1.255 and 21.1.256 each
passed 622 tests, with 202 environment-dependent skips and no failures. The
21.1.256 run used a clean build: NeoGradle's incremental recompilation of the
single changed loader overlay otherwise lost the cached Minecraft classes.
That failed preparation did not reach project compilation or tests. The three required-hook
contracts also passed against the repository's Sable 2.0.3 defaults. The loader,
fused driver and native step gate each require exactly one injection match.

Real JNI smoke tests on Linux and Windows each passed 26 assertions, including
4,448 collision callbacks. The packaged binaries match the tested payloads:

- Windows SHA256: `19105f177b03ea37c308c9d9fc58de55867bbd6d3cbd850c249d72b24c47fcc6`.
- Linux SHA256: `2c0a7dd931e99971bd29f53eccb5e0cdeaafeb46a2d30ef4b20904ce242bd108`.

The Linux payload requires glibc 2.34 or newer. Kinetic's private configured
Java 25 image does not establish its actual libc baseline. Native loading in
that game container, merged-classloader startup, physics-staff movement and
portal ignition are still pending. Plain-JVM smoke tests do not establish
those results. The candidate has not been installed at this checkpoint.
