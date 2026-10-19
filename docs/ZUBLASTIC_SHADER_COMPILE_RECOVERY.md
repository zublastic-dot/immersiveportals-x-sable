# Shader compilation recovery (.77)

## Observed failure

On 2026-10-19, Create Submarine 3.4.4's `vortex_pixel.fsh` failed GLSL
compilation. IP .76 had already stored that shader's type/name in ThreadLocals
at `compileShaderInternal` HEAD. Its RETURN injector did not run when compilation
threw, so subsequent shaders failed the stale-context assertion instead of
reporting their own result. The later `particle.json` and Iris `basic.json`
errors were part of this cascade. The original invalid shader is a separate
repair; IP must not suppress it.

## Change

The existing IOUtils source redirect now receives the enclosing method's
arguments directly, using Mixin's supported invocation-redirect argument
capture. Both lifecycle injectors and both ThreadLocals are removed. There is
no context to clear, restore, or share across nested/concurrent compilations.
Transformation rules and once-per-shader diagnostic logging are unchanged.

The target method body is not overwritten or wrapped. Iris can retain its
injections and local-variable captures. The redirect signature is checked
against the actual Minecraft 1.21.1 `Program.compileShaderInternal` bytecode.

## Validation and limits

Production-handler regressions cover failed reads, transformation exceptions,
compiler failure after source reading, Error propagation, nested reads,
concurrent readers, and shader type/name matching. An opt-in hidden OpenGL test
passes invalid GLSL through the real redirect, verifies compiler rejection, then
successfully compiles the repaired shader through the same redirect. This is
not a substitute for the combined live NeoForge/Iris resource-reload check.

Build with Java 21 and the normal Gradle test/build/native-bundle gates. Owner
validation uses NeoForge 21.1.264 and Sable 2.0.6. `-PdhGlTests=true` enables the
OpenGL regression. Exact commands, test XML, source SHA and artifact SHA belong
in the immutable owned-CI receipt, with live acceptance recorded separately.

## Native provenance

No native source changed. Preserve both native entries from the exact installed
.76 JAR, SHA-256
`d1e6176206a47a33d1e0b4917ec10da4300155bf25583781cd13cf488821799b`,
built from `6ac7c849c32808937de22ab1e9d0e0587fcaae64`:

| Entry | SHA-256 |
| --- | --- |
| `natives_ipl/sable_rapier_x86_64_windows.dll` | `19105f177b03ea37c308c9d9fc58de55867bbd6d3cbd850c249d72b24c47fcc6` |
| `natives_ipl/libsable_rapier_x86_64_linux.so` | `2c0a7dd931e99971bd29f53eccb5e0cdeaafeb46a2d30ef4b20904ce242bd108` |

This follows the unchanged-native owner-trial path in
`docs/ZUBLASTIC_CHUNK_TICKETS.md`; it is not a new native build or native ABI
certification. IP remains a shared mod, so deployment should use the ordinary
ModSync family update rather than disguising the JAR as a client-only adapter.
