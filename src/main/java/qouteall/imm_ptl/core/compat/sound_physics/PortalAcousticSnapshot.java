package qouteall.imm_ptl.core.compat.sound_physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Owner-thread copies for cross-world acoustic rays. No published object retains a Level,
 * SubLevel, block entity, or live reader. Unknown cells are NOT air. Ordinary SPA scenes and
 * their Sable/Create providers are unchanged; this is only the geometry for a portal route.
 *
 * <p>Capture is incremental, capped at 32K cells/16 spaces and four cached requests. All requests
 * share four milliseconds of work per 50ms window. One mod's shape callback can exceed that
 * cooperative budget; we never interrupt such a callback. Completed copies refresh at 250ms,
 * expire at one second, and are atomically published only after all cells have been sampled.
 */
public final class PortalAcousticSnapshot {
    public static final int MAX_CELLS = 32768, MAX_SPACES = 16;
    private static final int MAX_REQUESTS = 4, MAX_RAY_CELLS = 1024;
    private static final long BUDGET_NS = 4_000_000L, WINDOW_NS = 50_000_000L;
    private static final long REFRESH_NS = 250_000_000L, EXPIRE_NS = 1_000_000_000L;
    private static final LinkedHashMap<Key, Entry> CACHE = new LinkedHashMap<>(8, .75f, true);
    private static long budgetWindow = Long.MIN_VALUE, budgetUsed;

    private PortalAcousticSnapshot() { }

    /** Call from the client thread; null means pending. A failed copy has a nonempty reason(). */
    public static synchronized Snapshot request(ClientLevel level, AABB bounds) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Acoustic capture requires client thread");
        Objects.requireNonNull(level);
        Grid grid = Grid.of(bounds);
        if (grid == null || grid.volume() > MAX_CELLS) return unavailable(bounds, "cell_budget");
        Key key = new Key(level, grid);
        Entry entry = CACHE.computeIfAbsent(key, k -> new Entry());
        while (CACHE.size() > MAX_REQUESTS) CACHE.remove(CACHE.keySet().iterator().next());
        long now = System.nanoTime();
        long window = Math.floorDiv(now, WINDOW_NS);
        if (window != budgetWindow) { budgetWindow = window; budgetUsed = 0; }
        if (budgetUsed < BUDGET_NS) {
            long before = System.nanoTime();
            if (entry.job == null && (entry.snapshot == null || now - entry.published >= REFRESH_NS)) {
                entry.job = prepare(level, grid);
            }
            Snapshot finished = entry.job == null ? null
                : entry.job.advance(Math.max(0, BUDGET_NS - budgetUsed - (System.nanoTime() - before)));
            budgetUsed += Math.max(0, System.nanoTime() - before);
            if (finished != null) {
                entry.snapshot = finished;
                entry.published = System.nanoTime();
                entry.job = null;
            }
        }
        return entry.snapshot != null && System.nanoTime() - entry.published < EXPIRE_NS ? entry.snapshot : null;
    }

    public static synchronized void clear() { CACHE.clear(); budgetWindow = Long.MIN_VALUE; budgetUsed = 0; }
    public static synchronized void retain(Collection<ClientLevel> levels) {
        CACHE.keySet().removeIf(k -> levels.stream().noneMatch(level -> level == k.level));
    }

    private record Key(ClientLevel level, Grid bounds) { }
    private static final class Entry { CaptureJob job; Snapshot snapshot; long published; }

    private static CaptureJob prepare(ClientLevel level, Grid grid) {
        AABB world = grid.box();
        List<Input> inputs = new ArrayList<>();
        inputs.add(new Input("root", Frame.identity(), grid, grid, true,
            loadedReader(level, p -> {
                if (level.isOutsideBuildHeight(p)) return Blocks.AIR.defaultBlockState();
                var chunk = level.getChunkSource().getChunk(p.getX() >> 4, p.getZ() >> 4, false);
                return chunk == null ? null : chunk.getBlockState(p);
            }), level.getMinBuildHeight(), level.getHeight()));
        // A Create scene cannot be replaced with root blocks: its moving geometry has a separate
        // coordinate frame. Until that immutable provider's space can be copied, refuse this route.
        int scanned = 0;
        for (var entity : level.entitiesForRendering()) {
            if (++scanned > 1024) return CaptureJob.failed(world, "entity_scan_budget");
            if (entity.getBoundingBox().intersects(world) && isContraption(entity.getClass()))
                return CaptureJob.failed(world, "create_snapshot_unavailable");
        }
        Set<UUID> seen = new HashSet<>();
        try {
            for (SubLevel sub : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(world.minX, world.minY, world.minZ, world.maxX, world.maxY, world.maxZ))) {
                if (sub.isRemoved() || !seen.add(sub.getUniqueId())) continue;
                if (inputs.size() == MAX_SPACES) return CaptureJob.failed(world, "space_budget");
                // An image needs the actual clipped half, not an uncut duplicate of the ship.
                if (IplStraddlePoseMap.isStraddling(sub, level) || IplDimAgnostic.getParentLevel(sub) != level)
                    return CaptureJob.failed(world, "straddling_snapshot_unavailable");
                var plot = sub.getPlot();
                var bb = plot.getBoundingBox();
                Grid domain = new Grid(bb.minX(), bb.minY(), bb.minZ(), bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
                Frame frame = Frame.from(sub.logicalPose(), domain.box().getCenter());
                Grid needed = Grid.of(transformBox(world, frame::worldToLocal));
                Grid selected = domain.intersect(needed);
                if (selected == null) continue;
                inputs.add(new Input("sable:" + sub.getUniqueId(), frame, selected, domain, false,
                    loadedReader(sub.getLevel(), p -> {
                        var chunk = plot.getChunk(new ChunkPos(p));
                        return chunk == null ? null : chunk.getBlockState(p);
                    }), sub.getLevel().getMinBuildHeight(), sub.getLevel().getHeight()));
            }
        } catch (RuntimeException ex) {
            return CaptureJob.failed(world, "sable_snapshot_unavailable");
        }
        return new CaptureJob(world, inputs, System::nanoTime);
    }

    private static boolean isContraption(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            if (c.getName().equals("com.simibubi.create.content.contraptions.AbstractContraptionEntity")) return true;
        return false;
    }

    /** Non-loading shape context: even neighboring shape queries cannot acquire chunk tickets. */
    private static Function<BlockPos, Sample> loadedReader(BlockGetter height, Function<BlockPos, BlockState> blocks) {
        BlockGetter context = new BlockGetter() {
            public BlockEntity getBlockEntity(BlockPos p) { return null; }
            public BlockState getBlockState(BlockPos p) { BlockState s = blocks.apply(p); return s == null ? Blocks.BEDROCK.defaultBlockState() : s; }
            public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
            public int getHeight() { return height.getHeight(); }
            public int getMinBuildHeight() { return height.getMinBuildHeight(); }
        };
        return p -> {
            BlockState state = blocks.apply(p);
            if (state == null) return null;
            // Shape-neighbor queries on an unknown chunk must not manufacture a clear ray.
            for (Direction d : Direction.values()) if (blocks.apply(p.relative(d)) == null) return null;
            FluidState fluid = state.getFluidState();
            return new Sample(state, fluid, state.getCollisionShape(context, p, CollisionContext.empty()), fluid.getShape(context, p));
        };
    }

    static record Sample(BlockState state, FluidState fluid, VoxelShape solidShape, VoxelShape fluidShape) { }
    static record Input(String id, Frame frame, Grid selected, Grid domain, boolean root,
                        Function<BlockPos, Sample> reader, int minHeight, int height) { }

    /** Package-visible seam uses the same copier in tests, with a deterministic monotonic clock. */
    static final class CaptureJob {
        private final AABB world;
        private final List<Input> inputs;
        private final LongSupplier clock;
        private final long started;
        private final Thread owner = Thread.currentThread();
        private final List<Space> spaces = new ArrayList<>();
        private final Map<BlockPos, Sample> samples = new HashMap<>();
        private int inputIndex, cellIndex;
        private String failure = "";
        private Snapshot result;

        CaptureJob(AABB world, List<Input> inputs, LongSupplier clock) {
            this.world = world; this.inputs = List.copyOf(inputs); this.clock = clock; this.started = clock.getAsLong();
            long total = 0;
            for (Input input : inputs) {
                long cells = input.selected.volume();
                if (cells > MAX_CELLS) { failure = "cell_budget"; break; }
                total += cells;
            }
            if (total > MAX_CELLS || total < 0) failure = "cell_budget";
            if (inputs.size() > MAX_SPACES) failure = "space_budget";
            if (inputs.isEmpty()) failure = "empty_geometry";
        }
        static CaptureJob failed(AABB world, String reason) {
            CaptureJob job = new CaptureJob(world, List.of(), System::nanoTime); job.failure = reason; return job;
        }
        Snapshot advance(long budgetNanos) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Capture job moved off owner thread");
            if (result != null) return result;
            if (!failure.isEmpty()) return result = unavailable(world, failure);
            long start = clock.getAsLong();
            if (start - started >= 500_000_000L) return result = unavailable(world, "capture_expired");
            while (inputIndex < inputs.size()) {
                if (budgetNanos <= 0 || clock.getAsLong() - start >= budgetNanos) return null;
                Input input = inputs.get(inputIndex);
                BlockPos p = input.selected.at(cellIndex++);
                try {
                    Sample sample = input.reader.apply(p);
                    if (sample != null) samples.put(p, sample);
                } catch (RuntimeException ignored) { /* failed shape evaluation remains unknown */ }
                if (cellIndex == input.selected.volume()) {
                    spaces.add(new Space(input.id, input.frame, input.selected, input.domain, input.root,
                        samples, input.minHeight, input.height));
                    samples.clear(); cellIndex = 0; inputIndex++;
                }
            }
            if (clock.getAsLong() - started >= 500_000_000L) return result = unavailable(world, "capture_expired");
            return result = new Snapshot(world, spaces, "");
        }
    }

    /** Immutable affine transform. Basis vectors are copied values; normals use inverse transpose. */
    public record Frame(Vec3 localOrigin, Vec3 worldOrigin, Vec3 x, Vec3 y, Vec3 z) {
        public Frame {
            if (!finite(localOrigin) || !finite(worldOrigin) || !finite(x) || !finite(y) || !finite(z)
                || Math.abs(x.dot(y.cross(z))) < 1e-9) throw new IllegalArgumentException("Invalid acoustic frame");
        }
        public static Frame identity() { return new Frame(Vec3.ZERO, Vec3.ZERO, new Vec3(1,0,0), new Vec3(0,1,0), new Vec3(0,0,1)); }
        static Frame from(Pose3dc pose, Vec3 anchor) {
            Vec3 origin = pose.transformPosition(anchor);
            return new Frame(anchor, origin, pose.transformPosition(anchor.add(1,0,0)).subtract(origin),
                pose.transformPosition(anchor.add(0,1,0)).subtract(origin), pose.transformPosition(anchor.add(0,0,1)).subtract(origin));
        }
        public Vec3 localToWorld(Vec3 p) { return worldOrigin.add(localToWorldDirection(p.subtract(localOrigin))); }
        public Vec3 worldToLocal(Vec3 p) { return localOrigin.add(worldToLocalDirection(p.subtract(worldOrigin))); }
        public Vec3 localToWorldDirection(Vec3 v) { return x.scale(v.x).add(y.scale(v.y)).add(z.scale(v.z)); }
        public Vec3 worldToLocalDirection(Vec3 v) {
            double d = x.dot(y.cross(z));
            return new Vec3(v.dot(y.cross(z))/d, v.dot(z.cross(x))/d, v.dot(x.cross(y))/d);
        }
        public Vec3 localNormalToWorld(Vec3 normal) {
            double d = x.dot(y.cross(z));
            return y.cross(z).scale(normal.x/d).add(z.cross(x).scale(normal.y/d)).add(x.cross(y).scale(normal.z/d)).normalize();
        }
    }

    public static final class Space implements BlockGetter {
        private final String id;
        private final Frame frame;
        private final Grid selected, domain;
        private final boolean root;
        private final Map<BlockPos, Sample> cells;
        private final int minHeight, height;
        Space(String id, Frame frame, Grid selected, Grid domain, boolean root, Map<BlockPos, Sample> cells, int minHeight, int height) {
            this.id = id; this.frame = frame; this.selected = selected; this.domain = domain; this.root = root;
            this.cells = Map.copyOf(cells); this.minHeight = minHeight; this.height = height;
        }
        public String acousticId() { return id; }
        public Frame frame() { return frame; }
        public AABB localBounds() { return selected.box(); }
        public AABB worldBounds() { return transformBox(domain.box(), frame::localToWorld); }
        public boolean known(BlockPos p) { return cells.containsKey(p); }
        public boolean covers(Vec3 a, Vec3 b) { return selected.contains(BlockPos.containing(worldToLocal(a))) && selected.contains(BlockPos.containing(worldToLocal(b))); }
        public Vec3 worldToLocal(Vec3 p) { return frame.worldToLocal(p); }
        public Vec3 localToWorld(Vec3 p) { return frame.localToWorld(p); }
        public Vec3 worldToLocalDirection(Vec3 v) { return frame.worldToLocalDirection(v); }
        public Vec3 localToWorldDirection(Vec3 v) { return frame.localToWorldDirection(v); }
        public BlockState getBlockState(BlockPos p) { Sample s = cells.get(p); return s == null ? Blocks.BEDROCK.defaultBlockState() : s.state; }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos p) { return null; }
        public int getMinBuildHeight() { return minHeight; }
        public int getHeight() { return height; }
    }

    public record BlockRef(Space space, BlockPos pos) { public BlockRef { pos = pos.immutable(); } }
    public enum Status { HIT, MISS, UNKNOWN }
    public record RayResult(Status status, Space space, BlockHitResult localHit,
                            Vec3 worldLocation, Vec3 worldNormal, String reason) { }

    public static final class Snapshot {
        private final AABB bounds;
        private final List<Space> spaces;
        private final String reason;
        Snapshot(AABB bounds, List<Space> spaces, String reason) {
            this.bounds = bounds; this.spaces = List.copyOf(spaces); this.reason = reason;
        }
        public AABB bounds() { return bounds; }
        public List<Space> spaces() { return spaces; }
        public String reason() { return reason; }
        public boolean covers(Vec3 a, Vec3 b) { return finite(a) && finite(b) && contains(bounds, a) && contains(bounds, b); }
        public BlockRef blockAt(Vec3 world) {
            if (!reason.isEmpty() || !contains(bounds, world)) return null;
            BlockRef rootRef = null;
            for (Space space : spaces) {
                BlockPos p = BlockPos.containing(space.worldToLocal(world));
                if (!space.domain.contains(p)) continue;
                if (!space.known(p)) return null;
                BlockRef ref = new BlockRef(space, p);
                if (!space.getBlockState(p).isAir()) return ref;
                if (space.root) rootRef = ref;
            }
            return rootRef;
        }
        public RayResult rayCast(Vec3 start, Vec3 end, BlockRef ignored) {
            if (!reason.isEmpty()) return unknown(start, reason);
            if (spaces.isEmpty()) return unknown(start, "empty_geometry");
            if (!covers(start, end)) return unknown(start, "outside_snapshot");
            RayResult closest = null;
            double distance = Double.POSITIVE_INFINITY;
            for (Space space : spaces) {
                RayResult result = trace(space, start, end, ignored);
                if (result == null) continue;
                double d = start.distanceToSqr(result.worldLocation);
                if (d < distance || d == distance && result.status == Status.UNKNOWN) { closest = result; distance = d; }
            }
            return closest == null ? new RayResult(Status.MISS, null, null, end, Vec3.ZERO, "") : closest;
        }
    }

    private static RayResult trace(Space space, Vec3 start, Vec3 end, BlockRef ignored) {
        Vec3 a = space.worldToLocal(start), b = space.worldToLocal(end), delta = b.subtract(a);
        double[] range = interval(a, b, space.domain.box());
        if (range == null) return null;
        double t = range[0];
        Vec3 entry = a.add(delta.scale(Math.min(range[1], t + 1e-9)));
        int x = (int)Math.floor(entry.x), y = (int)Math.floor(entry.y), z = (int)Math.floor(entry.z);
        int sx = sign(delta.x), sy = sign(delta.y), sz = sign(delta.z);
        double tx = boundary(a.x, delta.x, x, sx), ty = boundary(a.y, delta.y, y, sy), tz = boundary(a.z, delta.z, z, sz);
        double dx = delta.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1/delta.x);
        double dy = delta.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1/delta.y);
        double dz = delta.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1/delta.z);
        for (int i = 0; i < MAX_RAY_CELLS && t <= range[1] + 1e-10; i++) {
            BlockPos p = new BlockPos(x,y,z);
            if (!space.domain.contains(p)) return null;
            Sample sample = space.cells.get(p);
            if (sample == null) return unknown(start.lerp(end, t), "unknown_cell:" + space.id);
            if (ignored == null || ignored.space != space || !ignored.pos.equals(p)) {
                BlockHitResult hit = sample.solidShape.clip(a, b, p);
                BlockHitResult fluid = sample.fluidShape.clip(a, b, p);
                if (fluid != null && (hit == null || a.distanceToSqr(fluid.getLocation()) < a.distanceToSqr(hit.getLocation()))) hit = fluid;
                if (hit != null) {
                    var normal = hit.getDirection().getNormal();
                    return new RayResult(Status.HIT, space, hit, space.localToWorld(hit.getLocation()),
                        space.frame.localNormalToWorld(new Vec3(normal.getX(),normal.getY(),normal.getZ())), "");
                }
            }
            double next = Math.min(tx, Math.min(ty, tz));
            if (!Double.isFinite(next) || next > range[1]) return null;
            if (tx <= next + 1e-12) { x += sx; tx += dx; }
            if (ty <= next + 1e-12) { y += sy; ty += dy; }
            if (tz <= next + 1e-12) { z += sz; tz += dz; }
            t = next;
        }
        return unknown(start.lerp(end, Math.min(1,t)), "ray_budget");
    }

    private static Snapshot unavailable(AABB bounds, String reason) { return new Snapshot(bounds, List.of(), reason); }
    private static RayResult unknown(Vec3 point, String reason) { return new RayResult(Status.UNKNOWN, null, null, point, Vec3.ZERO, reason); }
    private static int sign(double d) { return d > 0 ? 1 : d < 0 ? -1 : 0; }
    private static double boundary(double a, double d, int cell, int sign) { return sign == 0 ? Double.POSITIVE_INFINITY : (cell + (sign > 0 ? 1 : 0) - a)/d; }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean contains(AABB b, Vec3 p) { return p.x >= b.minX && p.x < b.maxX && p.y >= b.minY && p.y < b.maxY && p.z >= b.minZ && p.z < b.maxZ; }
    private static double[] interval(Vec3 a, Vec3 b, AABB box) {
        double near = 0, far = 1;
        double[] s = {a.x,a.y,a.z}, d = {b.x-a.x,b.y-a.y,b.z-a.z};
        double[] lo = {box.minX,box.minY,box.minZ}, hi = {box.maxX,box.maxY,box.maxZ};
        for (int i=0;i<3;i++) {
            if (Math.abs(d[i]) < 1e-15) { if (s[i] < lo[i] || s[i] >= hi[i]) return null; continue; }
            double first=(lo[i]-s[i])/d[i], second=(hi[i]-s[i])/d[i];
            near=Math.max(near,Math.min(first,second));far=Math.min(far,Math.max(first,second));
            if (near > far) return null;
        }
        return new double[]{near,far};
    }
    private static AABB transformBox(AABB b, Function<Vec3,Vec3> transform) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(int i=0;i<8;i++) {
            Vec3 p=transform.apply(new Vec3((i&1)==0?b.minX:b.maxX,(i&2)==0?b.minY:b.maxY,(i&4)==0?b.minZ:b.maxZ));
            minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);
            maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);
        }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }
    static record Grid(int x0,int y0,int z0,int x1,int y1,int z1) {
        static Grid of(AABB b) {
            if(b==null || !finite(new Vec3(b.minX,b.minY,b.minZ)) || !finite(new Vec3(b.maxX,b.maxY,b.maxZ))
                || b.minX < -30_000_000 || b.maxX > 30_000_000 || b.minZ < -30_000_000 || b.maxZ > 30_000_000
                || b.minY < -30_000_000 || b.maxY > 30_000_000) return null;
            Grid g=new Grid((int)Math.floor(b.minX),(int)Math.floor(b.minY),(int)Math.floor(b.minZ),
                (int)Math.ceil(b.maxX),(int)Math.ceil(b.maxY),(int)Math.ceil(b.maxZ));
            return g.x1>g.x0 && g.y1>g.y0 && g.z1>g.z0 ? g : null;
        }
        long volume() {
            long x=(long)x1-x0,y=(long)y1-y0,z=(long)z1-z0;
            if(x<=0||y<=0||z<=0||x>MAX_CELLS||y>MAX_CELLS||z>MAX_CELLS) return Long.MAX_VALUE;
            return x*y*z;
        }
        boolean contains(BlockPos p) { return p.getX()>=x0&&p.getX()<x1&&p.getY()>=y0&&p.getY()<y1&&p.getZ()>=z0&&p.getZ()<z1; }
        Grid intersect(Grid b) {
            if(b==null)return null;
            Grid g=new Grid(Math.max(x0,b.x0),Math.max(y0,b.y0),Math.max(z0,b.z0),Math.min(x1,b.x1),Math.min(y1,b.y1),Math.min(z1,b.z1));
            return g.x1>g.x0&&g.y1>g.y0&&g.z1>g.z0?g:null;
        }
        BlockPos at(int index) { int dx=x1-x0,dy=y1-y0;return new BlockPos(x0+index%dx,y0+(index/dx)%dy,z0+index/(dx*dy)); }
        AABB box() { return new AABB(x0,y0,z0,x1,y1,z1); }
    }
}
