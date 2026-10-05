# Destination-native portal color, 2026-10-05

The owner observed a warm soul-torch halo through the portal despite blue light inside the destination room, and a pale square behind a redstone wall torch. Installed baseline: fork .60, Colorful Lighting 2.5.1, Iris 1.8.14-beta.1 with Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5. The baseline was reproduced in Portal Lab.

Colorful Lighting has a singleton active-world light engine. Portal views change Minecraft.level temporarily without giving that engine independent destination-world storage. The compatibility path builds native block-emission RGB from Colorful's own Config API for each exact destination ClientLevel identity. It never imports colors from the active world's light engine, never feeds propagated portal light back into its own sources, and does not enable experimental transport or gameplay sunlight.

Chunk workers enqueue bounded requests and sample immutable publications. Client ticks capture loaded blocks within a fixed work budget, then schedule terrain rebuilds through the existing Sodium readiness-aware adapter. Pending captures use scalar fallback. No chunks are loaded by the color adapter. Native entity and held-item dynamic lights in remote views are outside this correction.

The receiving field previously accepted only air. Non-colliding, non-occluding, zero-opacity decorations now retain the surrounding receiving field; opaque and partial solid blocks remain conservative whole-cell occluders. Shared block-change routing handles both geometry and RGB updates.

Use `imm_ptl_client_debug portal_native_color` to inspect bounded work, publication and rebuild state. Exact build hashes and runtime acceptance belong in the canonical context record and local evidence under M:/PortalColorDiagnostics-20261005. Until those receipts exist, this is an implementation candidate, not a verified installed fix.
