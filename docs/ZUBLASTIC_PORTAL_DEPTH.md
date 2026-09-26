# Portal depth compatibility with packed UI targets

The 2026-09-26 Portal Lab bisection isolated AutoSeamBlend 1.0.3 (including its
bundled libraries) with a FAIL/PASS/FAIL remove/restore control. The exact source
baseline was `fef11c46b7c7d56f79fccade98a69920752f83d7` (IP/Sable .4). This change
stays inside IP/Sable; it does not modify Iris or AutoSeamBlend.

ApricityUI 1.2.4-hotfix can replace a RenderTarget depth texture's storage with
GL_DEPTH32F_STENCIL8 without changing its texture ID. IP's shader renderer uses
GL_DEPTH24_STENCIL8 on NVIDIA; its compatibility renderer has a depth-only
scratch target. Copying a packed 64-bit depth/stencil image into those different
formats is invalid. A failed copy leaves foreground depth absent, so a portal
may be drawn over closer blocks.

Both IP shader renderer paths now inspect the actual source and scratch depth
formats immediately before copying. When the source is packed depth/stencil,
the IP-owned destination is allocated with the same packed format. The rendered
source is never reallocated. Existing texture parameters are retained, the
draw framebuffer and active-unit texture binding are restored even on failure,
and no additional GL objects are retained. Matching formats do not reallocate.
Queries are deliberately not cached by object ID: foreign in-place storage
changes would invalidate that cache. The pre-existing depth-only source path
is unchanged. Only the first eight adjustments are logged per process.

Tests cover both shader renderer target formats, matching storage, repeated
frames, in-place source format changes, resize/recreation and visible allocation
failure. All 37 regression tests and the native-preserving build pass. These
tests do not render pixels.

## Bounded owner acceptance, 26 September 2026

The owner confirmed that foreground leaves correctly hide the portal with
AutoSeamBlend still enabled, and supplied a screenshot. The installed .5 artifact
was built from `44cf6eb13b21238b9250a8c7f4e46bcf6cbc1541`, SHA-256
`cf4148d0ba2d9a6cf1018d41e41d590dfd4626458d74de0ff98b2c7a54ababb1`.
Only IP/Sable changed from the prior failing 14-JAR restore; all thirteen other
root JAR hashes, including AutoSeamBlend 1.0.3, match that control.

The log records source texture 7 using `0x8cad` (GL_DEPTH32F_STENCIL8), while
IP-owned textures 63 and 65 initially used `0x88f0` (GL_DEPTH24_STENCIL8).
Both were adjusted once. Neither prior incompatible-depth/copy-format signature
nor the compatibility-renderer transition appears in the captured session.
This turns the earlier format-mismatch hypothesis into an observed mismatch
with a successful targeted repair. It does not isolate ApricityUI as a standalone
mod: its source path remains the explanation for the bundled storage change.

Scope: Minecraft 1.21.1, NeoForge 21.1.251, Iris 1.8.14-beta.1, Sodium 0.8.13,
ordinary Complementary Unbound r5.9.3, NVIDIA RTX 3070 Laptop, Portal Lab only.
Real Camera and DH are absent. This is owner-confirmed occlusion acceptance;
the candidate's unobstructed destination, reload, shaderless/Euphoria controls,
other GPUs and the full GregTest/Kinetic stack were not verified in this trial.
Other GL object-label and shader/teleport/watchdog diagnostics remain; this is
not a globally clean-log claim or a performance measurement. The .4 artifact is
retained for rollback. Raw logs and the owner image remain local under
`M:/PortalASBDepth-20260926/`; canonical context records sanitized findings.
