# Portal image clipping investigation, 25 September 2026

The `.3` Euphoria repair stopped repeated pipeline destruction and the owner
confirmed FPS recovery. The destination view still fails: the owner sees a red
portal with an Overworld silhouette under shaders, and solid red without them.
An OpenGL 1281 is recorded before renderer fallback; that stack does not identify
the operation that originally set the error. Do not equate a fog-coloured portal
or a sampled frame rate with correct destination-world rendering.

Source inspection found two duplicated program-bind hooks that unconditionally
enable clip distance 0 while any portal view is active, including when a bound
copy/composite shader does not write that distance. They also change the hardware
enable without updating `FrontClipping.isClippingEnabled`, so its conditional
disable can leave the plane enabled. OpenGL defines enabled but unwritten clip
distance values as undefined ([GLSL 4.60 specification, built-in outputs](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html)). These are real state-management defects, but their
responsibility for the owner's exact red view requires a new runtime comparison.

The candidate unifies both bind paths and enables only clipping uniforms that
the current shader actually carries, within their corresponding portal/Sable
scope. Copy passes disable those owned distances; subsequent geometry binds
reassert the required planes. Both independent Sable cuts remain supported.
FrontClipping enable/disable reaches GL even if its logical flag already has the
requested value. Mojang program deletion/relink invalidates cached uniform
locations and sublevel registrations so reused handles are resolved again.

Seven deterministic tests exercise the production binding state with a fake GL
driver: geometry/composite/geometry, two ship cuts across a copy, optimized-out
uniforms, external state changes, scope exit, handle reuse and ship-only draws.
They do not draw pixels or establish live visual acceptance. Keep the Euphoria
pack-cache tests and existing chunk-ticket/packet-capture tests passing.

Runtime acceptance requires the actual Nether view with shaders off and Euphoria
on, stable FPS, a real reload, and retention of portal/Sable clipping. Use a
client-only trial with the existing shader settings and retain the previous JAR.
Source and build identities, observations and remaining limits belong in canonical
context and `M:/PortalRender-20260925/REPORT.md`. Do not change the server, world,
mod lineup or approved ModSync inventory to make this rendering trial pass.
