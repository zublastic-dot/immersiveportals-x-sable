package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.teleportation.PortalSoundManager;
import qouteall.imm_ptl.core.teleportation.PortalSoundPath;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Two immutable acoustic worlds joined at one rectangular aperture. SPA rays are
 * expressed in the listener frame; material lookup retains the original local
 * block/space. Reflection and occlusion remain SPA's algorithms and filters.
 */
final class PortalAcousticScene implements InvocationHandler {
    private final PortalSoundManager.Route route;
    private final PortalSoundPath.Frame frame;
    private final PortalAcousticSnapshot.Snapshot source, listener;
    private final Map<Object, Binding> bindings = new IdentityHashMap<>();
    private final Map<String, Binding> byName = new HashMap<>();
    private final Map<PortalAcousticSnapshot.Space, Object> sourceSpaces = new IdentityHashMap<>();
    private final Map<PortalAcousticSnapshot.Space, Object> listenerSpaces = new IdentityHashMap<>();
    private volatile Object proxy;
    private Object boundary, info, domain;
    private Constructor<?> blockConstructor, hitConstructor;

    PortalAcousticScene(PortalSoundManager.Route route, PortalAcousticSnapshot.Snapshot source,
                        PortalAcousticSnapshot.Snapshot listener) {
        this.route = route; this.frame = route.frame(); this.source = source; this.listener = listener;
    }

    PortalAcousticSnapshot.Snapshot sourceSnapshot() { return source; }
    PortalAcousticSnapshot.Snapshot listenerSnapshot() { return listener; }

    synchronized Object asSpaScene() throws ReflectiveOperationException {
        if (proxy != null) return proxy;
        ClassLoader loader = getClass().getClassLoader();
        Class<?> sceneType = Class.forName("com.sonicether.soundphysics.acoustic.AcousticScene", false, loader);
        Class<?> spaceType = Class.forName("com.sonicether.soundphysics.acoustic.AcousticSpace", false, loader);
        Class<?> refType = Class.forName("com.sonicether.soundphysics.acoustic.AcousticBlockRef", false, loader);
        Class<?> hitType = Class.forName("com.sonicether.soundphysics.acoustic.AcousticRayHit", false, loader);
        blockConstructor = refType.getConstructor(spaceType, BlockPos.class);
        hitConstructor = hitType.getConstructor(BlockHitResult.class, spaceType, Vec3.class, Vec3.class, boolean.class);
        for (var space : source.spaces()) addSpace(loader, spaceType, space, true);
        for (var space : listener.spaces()) addSpace(loader, spaceType, space, false);
        boundary = Proxy.newProxyInstance(loader, new Class<?>[]{spaceType}, (p, m, a) -> switch (m.getName()) {
            case "acousticId" -> "ip:aperture-boundary";
            case "getBlockState" -> Blocks.OBSIDIAN.defaultBlockState();
            case "getFluidState" -> Blocks.OBSIDIAN.defaultBlockState().getFluidState();
            case "getBlockEntity" -> null;
            case "getHeight" -> 4096;
            case "getMinBuildHeight" -> -2048;
            default -> defaultCall(p, m, a);
        });
        String reason = !source.reason().isEmpty() ? source.reason() : !listener.reason().isEmpty() ? listener.reason() : "bounded_portal_snapshot";
        info = Class.forName("com.sonicether.soundphysics.acoustic.AcousticSceneInfo", false, loader)
            .getConstructor(String.class, boolean.class, boolean.class, String.class)
            .newInstance("ip_portal", source.spaces().size() > 1 || listener.spaces().size() > 1, true, reason);
        domain = Class.forName("com.sonicether.soundphysics.acoustic.AcousticDomainKey", false, loader)
            .getMethod("of", Object.class, String.class).invoke(null, this, "ip:" + route.portalId());
        proxy = Proxy.newProxyInstance(loader, new Class<?>[]{sceneType}, this);
        return proxy;
    }

    private void addSpace(ClassLoader loader, Class<?> type, PortalAcousticSnapshot.Space space, boolean sourceSide) {
        String id = (sourceSide ? "ip:source:" : "ip:listener:") + space.acousticId();
        Object proxySpace = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "acousticId" -> id;
            case "getBlockState" -> space.getBlockState((BlockPos) a[0]);
            case "getFluidState" -> space.getFluidState((BlockPos) a[0]);
            case "getBlockEntity" -> null;
            case "getHeight" -> space.getHeight();
            case "getMinBuildHeight" -> space.getMinBuildHeight();
            default -> defaultCall(p, m, a);
        });
        Binding binding = new Binding(space, sourceSide, proxySpace);
        bindings.put(proxySpace, binding); byName.put(id, binding);
        (sourceSide ? sourceSpaces : listenerSpaces).put(space, proxySpace);
    }

    private record Binding(PortalAcousticSnapshot.Space space, boolean sourceSide, Object proxy) {}

    @Override public Object invoke(Object ignoredProxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "rayCast" -> ray((Vec3) args[0], (Vec3) args[1], args[2]);
            case "blockAt" -> block((Vec3) args[0]);
            case "resolveBlockRef" -> {
                Binding binding = byName.get((String) args[0]);
                yield binding == null ? null : blockConstructor.newInstance(binding.proxy(), args[1]);
            }
            case "toLocalPosition", "toWorldPosition", "toLocalDirection", "toWorldDirection" -> transform(method.getName(), args);
            case "isSegmentAuthoritative" -> authoritative((Vec3) args[0], (Vec3) args[1]);
            case "segmentAuthorityFailure" -> authoritative((Vec3) args[0], (Vec3) args[1]) ? "none" : "ip_snapshot_boundary";
            case "acousticDomainKey" -> domain;
            case "sceneInfo" -> info;
            // Geometry identity includes both immutable publications and the exact
            // link/pose; no cross-world or cross-pose reflection-cache reuse.
            case "geometryCacheIdentity" -> this;
            case "acousticVersion", "acousticTopologyVersion", "portalRelativePoseSignature" -> route.epoch();
            case "portalGeometryUnavailableReason" -> "bounded_portal_snapshot";
            case "supportsExactCrossTickReflectionReuse", "supportsOrderedOcclusionSegments" -> false;
            default -> defaultCall(ignoredProxy, method, args);
        };
    }

    private Object transform(String operation, Object[] args) throws ReflectiveOperationException {
        Binding binding = args[0] instanceof String name ? byName.get(name)
            : bindings.get(args[0].getClass().getMethod("space").invoke(args[0]));
        Vec3 value = (Vec3) args[1];
        if (binding == null) return value;
        boolean local = operation.startsWith("toLocal"), direction = operation.endsWith("Direction");
        if (local) {
            Vec3 physical = binding.sourceSide() ? (direction ? frame.inverseVector(value) : frame.inverse(value)) : value;
            return direction ? binding.space().worldToLocalDirection(physical) : binding.space().worldToLocal(physical);
        }
        Vec3 physical = direction ? binding.space().localToWorldDirection(value) : binding.space().localToWorld(value);
        return binding.sourceSide() ? (direction ? frame.transformVector(physical) : frame.transform(physical)) : physical;
    }

    private Object block(Vec3 position) throws ReflectiveOperationException {
        boolean sourceSide = sourceSide(position);
        var ref = (sourceSide ? source : listener).blockAt(sourceSide ? frame.inverse(position) : position);
        if (ref == null) return blockConstructor.newInstance(boundary, BlockPos.containing(position));
        return blockConstructor.newInstance((sourceSide ? sourceSpaces : listenerSpaces).get(ref.space()), ref.pos());
    }

    private boolean sourceSide(Vec3 position) { return position.subtract(frame.destination()).dot(frame.destinationNormal()) > 0; }

    private Object ray(Vec3 from, Vec3 to, Object ignore) throws ReflectiveOperationException {
        Split split = split(frame, from, to);
        boolean first = sourceSide(from);
        if (split == null) return cast(first, from, to, ignore);
        Vec3 direction = to.subtract(from).normalize();
        Object before = cast(first, from, split.point().subtract(direction.scale(1e-5)), ignore);
        BlockHitResult hit = (BlockHitResult) before.getClass().getMethod("localHit").invoke(before);
        if (hit.getType() == HitResult.Type.BLOCK) return before;
        // Outside the opening these two coordinate charts are not connected. A
        // conservative unknown boundary is not evidence of an infinite obsidian wall.
        if (!split.open()) return boundaryHit(split.point(), first ? frame.destinationNormal() : frame.destinationNormal().scale(-1), false);
        return cast(!first, split.point().add(direction.scale(1e-5)), to, ignore);
    }

    private Object cast(boolean sourceSide, Vec3 from, Vec3 to, Object ignored) throws ReflectiveOperationException {
        PortalAcousticSnapshot.BlockRef ignore = null;
        if (ignored != null) {
            Binding binding = bindings.get(ignored.getClass().getMethod("space").invoke(ignored));
            if (binding != null && binding.sourceSide() == sourceSide) ignore = new PortalAcousticSnapshot.BlockRef(
                binding.space(), (BlockPos) ignored.getClass().getMethod("pos").invoke(ignored));
        }
        var result = (sourceSide ? source : listener).rayCast(sourceSide ? frame.inverse(from) : from,
            sourceSide ? frame.inverse(to) : to, ignore);
        if (result.status() == PortalAcousticSnapshot.Status.UNKNOWN) {
            return boundaryHit(sourceSide ? frame.transform(result.worldLocation()) : result.worldLocation(),
                from.subtract(to).normalize(), false);
        }
        Object space = (sourceSide ? sourceSpaces : listenerSpaces).get(result.space());
        if (space == null) space = boundary;
        BlockHitResult localHit = result.localHit();
        if (localHit == null) {
            Vec3 localEnd = sourceSide ? frame.inverse(to) : to;
            localHit = BlockHitResult.miss(localEnd, Direction.getNearest(from.x - to.x, from.y - to.y, from.z - to.z), BlockPos.containing(localEnd));
        }
        return hitConstructor.newInstance(localHit, space,
            sourceSide ? frame.transform(result.worldLocation()) : result.worldLocation(),
            sourceSide ? frame.transformVector(result.worldNormal()) : result.worldNormal(), true);
    }

    private Object boundaryHit(Vec3 point, Vec3 normal, boolean known) throws ReflectiveOperationException {
        Direction direction = Direction.getNearest(normal.x, normal.y, normal.z);
        return hitConstructor.newInstance(new BlockHitResult(point, direction, BlockPos.containing(point), false),
            boundary, point, normal, known);
    }

    private boolean authoritative(Vec3 from, Vec3 to) {
        Split split = split(frame, from, to);
        if (split == null) return covers(sourceSide(from), from, to);
        return split.open() && covers(sourceSide(from), from, split.point()) && covers(!sourceSide(from), split.point(), to);
    }
    private boolean covers(boolean sourceSide, Vec3 from, Vec3 to) {
        return (sourceSide ? source : listener).rayCast(sourceSide ? frame.inverse(from) : from,
            sourceSide ? frame.inverse(to) : to, null).status() != PortalAcousticSnapshot.Status.UNKNOWN;
    }

    record Split(Vec3 point, boolean open) {}
    static Split split(PortalSoundPath.Frame frame, Vec3 from, Vec3 to) {
        double a = from.subtract(frame.destination()).dot(frame.destinationNormal());
        double b = to.subtract(frame.destination()).dot(frame.destinationNormal());
        if ((a > 0) == (b > 0) || Math.abs(a - b) < 1e-10) return null;
        Vec3 point = from.lerp(to, a / (a - b));
        Vec3 relative = point.subtract(frame.destination());
        return new Split(point, Math.abs(relative.dot(frame.destinationU())) <= frame.halfWidth() + 1e-6
            && Math.abs(relative.dot(frame.destinationV())) <= frame.halfHeight() + 1e-6);
    }

    private static Object defaultCall(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
            case "toString" -> "IP portal acoustic snapshot";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        };
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
        throw new UnsupportedOperationException("Unverified SPA contract " + method);
    }
}
