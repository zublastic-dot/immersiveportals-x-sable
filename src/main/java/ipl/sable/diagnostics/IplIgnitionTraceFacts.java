package ipl.sable.diagnostics;

import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import java.util.HashSet;
import java.util.Set;
import static ipl.sable.diagnostics.IplIgnitionTrace.Side.SERVER;

/** Pure observations of an already-resolved plot; never resolves or routes a lookup. */
public final class IplIgnitionTraceFacts {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private record Context(Player player, BlockPos hit, Set<String> plots) {}
    private IplIgnitionTraceFacts() {}

    public static IplIgnitionTrace.Scope begin(Player player, BlockHitResult hit) {
        if (!IplIgnitionTrace.isTracing(SERVER) || CURRENT.get() != null) return () -> {};
        Context context = new Context(player, hit.getBlockPos(), new HashSet<>());
        CURRENT.set(context);
        return () -> { if (CURRENT.get() == context) CURRENT.remove(); };
    }

    public static String level(Level level) {
        return level == null ? "null" : level.dimension().location().toString();
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
