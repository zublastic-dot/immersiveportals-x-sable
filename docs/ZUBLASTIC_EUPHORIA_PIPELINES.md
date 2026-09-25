# Euphoria portal pipeline compatibility

## Incident and exact scope

On 25 September 2026 the owner reported single-digit FPS near an Immersive
Portal with Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5. Ordinary
Complementary did not exhibit that collapse. The supplied F3 screenshot shows
4 FPS, about 8 GiB/s allocation, a damaged portal view, and automatic fallback
to compatibility portal rendering.

A bounded 1 MiB copy of GregTest's log contains 77 pipeline creation messages
and 77 destruction messages. Around 21:36 local time they alternate between
Overworld and Nether about once per second. It also contains an OpenGL 1281
error during nested portal rendering. The FPS observation and GL error are
runtime evidence, not a benchmark of the candidate repair.

Installed inputs inspected directly:

- EuphoriaPatcher-1.10.5-r5.9.3-neoforge.jar:
  `fe64a1fad6b6f39a95f22ea6ba9506b712e36b96c46b80f1cf38fe144900a4b2`.
- iris-neoforge-1.8.14-beta.1+mc1.21.1.jar:
  `60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314`.
- Previous IP/Sable 0.5.1-zublastic.2 + IP 6.0.7:
  `7cffa076b891fef450e0df50aa7a751143a21e375f3057e79be5d0ac59733a12`.

Bytecode inspection establishes the mechanism: Euphoria's
`DimensionShaderRefresh.beforePreparePipeline` observes `Minecraft.level`
changing, calls `swapToDimension`, then destroys **all** Iris pipelines.
Its tick fallback can also rebuild them. Portal rendering deliberately swaps
the current client level while the outer dimension is still rendering.
Destruction therefore occurs inside a render, as well as repeatedly compiling
the same dimensions. Euphoria's default extra-dimension pack cache is zero,
so rebuilding its CPU shader-pack representation can also repeat.

## Repair

Version 0.5.1-zublastic.3 uses Iris's existing per-dimension GPU pipeline cache
and keeps each dimension's Euphoria ShaderPack alongside it. Euphoria's own
dimension normalization, macros and shader-pack constructor remain responsible
for building each pack. Returning to a cached view restores that dimension's
pack without rebuilding or destroying pipelines. Real travel uses the same
dimension selection as portal views.

The Euphoria global refresh entry points are replaced only for the exact
inspected Euphoria/Iris version pair. Shader-pack reloads and Iris pipeline
destruction clear the extra references. There is no independent GPU cache,
new render thread, per-frame logging, or settings downgrade. The additional
pack count is bounded by the distinct dimensions prepared in Iris's current
pipeline generation, whose cache already has that lifetime.

All new mixins are client-only and optional. Missing Euphoria/Iris or unknown
versions leave upstream behavior intact. Binding failures log a diagnostic
and restore upstream refresh rather than silently selecting wrong dimension
defines. Other IP/Sable fixes and packet diagnostics are preserved.

## Verification and limits

Regression tests exercise repeated nested dimension views, correct restoration
of outer-world macros, invalidation on shader reload, failed/skipped builds,
failed activation, and the optional-version gate. Existing chunk-ticket and
packet-capture tests remain required. Build instructions and unchanged native
payload requirements are in [ZUBLASTIC_CHUNK_TICKETS.md](ZUBLASTIC_CHUNK_TICKETS.md).

Live acceptance requires the exact candidate to load, then viewing the same
portal with Euphoria enabled, crossing it, and explicitly reloading shaders.
After initial compilation, ordinary frames must not repeatedly destroy/create
pipelines; dimensions must render correctly and FPS must recover. A passing
unit test or quiet main menu does not establish that result. Other shader,
DH, Sable, GL or portal-rendering defects are outside this narrow attribution.

Source, build, installation and live acceptance must be recorded separately
in canonical context and the local `M:/EuphoriaPortals-20260925/` report.
