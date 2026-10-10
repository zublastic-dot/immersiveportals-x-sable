package ipl.sable.diagnostics;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import java.util.HashSet;
import java.util.Set;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;

/** Pure observations of an already-resolved plot; never resolves or routes a lookup. */
public final class IplIgnitionTraceFacts {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private record Context(Player player, BlockPos hit, Set<String> plots, Set<String> blockReads) {}
    private IplIgnitionTraceFacts() {}

    public static IplIgnitionTrace.Scope begin(Player player, BlockHitResult hit) {
        if (!IplIgnitionTrace.isTracing(SERVER) || CURRENT.get() != null) return () -> {};
        Context context = new Context(player, hit.getBlockPos().immutable(), new HashSet<>(), new HashSet<>());
        CURRENT.set(context);
        return () -> { if (CURRENT.get() == context) CURRENT.remove(); };
    }

    public static String level(Level level) {
        return level == null ? "null" : level.dimension().location().toString();
    }

    /** Exact-click routing facts supplied by the application; never performs a lookup. */
    public static void observeRoute(String stage, Level caller, BlockPos pos, Object... fields) {
        if (!IplIgnitionTrace.isTracing(SERVER) || caller == null || caller.isClientSide()) return;
        Context context = CURRENT.get();
        if (context == null || !context.hit.equals(pos) || context.blockReads.size() >= 8) return;
        try {
            if (!context.blockReads.add(stage + ":" + level(caller))) return;
            Object[] withContext = new Object[fields.length + 4];
            withContext[0] = "lookup_level";
            withContext[1] = level(caller);
            withContext[2] = "pos";
            withContext[3] = pos.toShortString();
            System.arraycopy(fields, 0, withContext, 4, fields.length);
            IplIgnitionTrace.event(SERVER, stage, withContext);
        } catch (RuntimeException failure) {
            IplIgnitionTrace.event(SERVER, "block_read.observation_unavailable",
                "type", failure.getClass().getSimpleName());
        }
    }

    /** A chunk-cache result already resolved by application code, sampled at the exact hit. */
    public static void observeChunkRoute(String stage, Level caller, int chunkX, int chunkZ,
                                         LevelChunk result) {
        if (!IplIgnitionTrace.isTracing(SERVER) || caller == null || caller.isClientSide()) return;
        Context context = CURRENT.get();
        if (context == null || (context.hit.getX() >> 4) != chunkX
            || (context.hit.getZ() >> 4) != chunkZ || context.blockReads.size() >= 8) return;
        observeChunkFacts(stage, caller, context.hit, result, null);
    }

    /** Observe the chunk already selected by Level.getBlockState, without another route lookup. */
    public static void observeBlockRead(Level caller, BlockPos pos, LevelChunk chunk,
                                        BlockState result, boolean completed) {
        if (!IplIgnitionTrace.isTracing(SERVER) || caller.isClientSide()) return;
        Context context = CURRENT.get();
        if (context == null || !context.hit.equals(pos) || context.blockReads.size() >= 8) return;
        observeChunkFacts(completed ? "block_read.result" : "block_read.chunk", caller, pos, chunk, result);
    }

    private static void observeChunkFacts(String stage, Level caller, BlockPos pos, LevelChunk chunk,
                                          BlockState result) {
        Context context = CURRENT.get();
        try {
            if (!context.blockReads.add(stage + ":" + level(caller))) return;
            var facts = IplBlockReadProbe.describeChunk(chunk, pos);
            IplIgnitionTrace.event(SERVER, stage, "lookup_level", level(caller),
                "pos", pos.toShortString(), "chunk_class", facts.chunkClass(),
                "chunk_level", facts.level(), "chunk_pos", facts.position(),
                "min_y", facts.minY(), "section_index", facts.sectionIndex(),
                "section_count", facts.sectionCount(), "section_empty", facts.sectionEmpty(),
                "section_state", facts.sectionState(), "result", result);
        } catch (RuntimeException failure) {
            IplIgnitionTrace.event(SERVER, "block_read.observation_unavailable",
                "type", failure.getClass().getSimpleName());
        }
    }

    public static void observePlot(Level lookupLevel, LevelPlot resolved, int chunkX, int chunkZ,
                                   boolean localResult) {
        if (!IplIgnitionTrace.isTracing(SERVER) || lookupLevel == null || lookupLevel.isClientSide()) return;
        Context context = CURRENT.get();
        if (context == null || context.plots.size() >= 8) return;
        try {
            var owner = resolved == null ? null : resolved.getSubLevel();
            String key = level(lookupLevel) + ":" + (owner == null ? "null" : owner.getUniqueId());
            if (!context.plots.add(key)) return;
            IplIgnitionTrace.event(SERVER, "plot.resolved", "lookup_level", level(lookupLevel),
                "chunk_x", chunkX, "chunk_z", chunkZ, "local_result", localResult,
                "owner", owner == null ? null : owner.getUniqueId(),
                "storage_level", owner == null ? null : level(owner.getLevel()),
                "parent_level", owner == null ? null : level(IplDimAgnostic.getParentLevel(owner)),
                "removed", owner != null && owner.isRemoved());
            if (owner != null) {
                // Pose math only, using the exact owner returned by the application's lookup.
                // This is an input observation, not a second reach decision or ownership lookup.
                var eye = context.player.getEyePosition();
                var pose = owner.logicalPose();
                var localEye = pose.transformPositionInverse(eye);
                IplIgnitionTrace.event(SERVER, "plot.reach_inputs", "owner", owner.getUniqueId(),
                    "hit", context.hit.toShortString(), "eye", eye, "inverse_logical_eye", localEye,
                    "logical_position", pose.position(),
                    "block_aabb_distance_squared", new AABB(context.hit).distanceToSqr(localEye));
            }
        } catch (RuntimeException failure) {
            // Diagnostics must not turn an otherwise valid interaction into a failure.
            IplIgnitionTrace.event(SERVER, "plot.observation_unavailable", "type", failure.getClass().getSimpleName());
        }
    }
}
