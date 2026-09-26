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
failure. These tests do not render pixels. Runtime acceptance is pending an
exact-artifact Portal Lab trial with AutoSeamBlend still enabled, occluded and
clear viewpoints, shader reload and the shaderless control. The previous .4
artifact is retained for rollback.
