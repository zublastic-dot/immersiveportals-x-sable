package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Edit-driven occupied full-block cache for portal narrow phase, excluding portal openings. */
public final class IplPortalVolumeCache {

    private static final Map<UUID, List<BlockPos>> BLOCKS = new HashMap<>();
    private static final Map<UUID, Long> REVISIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_SEEN = new HashMap<>();
    private static long tick;

    private IplPortalVolumeCache() {}

    public static List<BlockPos> blocks(ServerSubLevel ship) {
        LAST_SEEN.put(ship.getUniqueId(), tick);
        return BLOCKS.computeIfAbsent(ship.getUniqueId(), ignored -> build(ship));
    }

    /** Starts a hosting-container scan. Entries not touched by its live ships are stale. */
    public static void beginTick() {
        tick++;
    }

    /** Marks a live ship even when no portal narrow phase queried its block list this tick. */
    public static void touch(ServerSubLevel ship) {
        LAST_SEEN.put(ship.getUniqueId(), tick);
    }

    /** Prevent unbounded UUID/block-list retention after assembly, split, removal or transit. */
    public static void prune() {
        LAST_SEEN.entrySet().removeIf(entry -> {
            if (entry.getValue() == tick) return false;
            UUID id = entry.getKey();
            BLOCKS.remove(id);
            REVISIONS.remove(id);
            return true;
        });
    }

    public static void invalidate(ServerSubLevel ship) {
        UUID id = ship.getUniqueId();
        BLOCKS.remove(id);
        REVISIONS.merge(id, 1L, Long::sum);
    }

    /** Changes only when LevelPlot reports a block edit, never while a ship moves. */
    public static long revision(ServerSubLevel ship) {
        return REVISIONS.getOrDefault(ship.getUniqueId(), 0L);
    }

    public static void clear() {
        BLOCKS.clear();
        REVISIONS.clear();
        LAST_SEEN.clear();
        tick = 0L;
    }

    private static List<BlockPos> build(ServerSubLevel ship) {
        ServerLevel level = (ServerLevel) ship.getLevel();
        var bounds = ship.getPlot().getBoundingBox();
        return collectBlocks(
            new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
            new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()), level::getBlockState
        );
    }

    static List<BlockPos> collectBlocks(
        BlockPos min, BlockPos max, Function<BlockPos, BlockState> readState
    ) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = readState.apply(pos);
                    // These invisible, non-colliding blocks mark the aperture. Treating
                    // them as occupied cubes turns a hollow carrier into a solid sheet:
                    // a small portal inside its opening can then admit the large frame
                    // to a spurious straddle session, creating destination-side contacts.
                    // Keep other blocks' existing whole-cube transit semantics.
                    if (!state.isAir() && !state.is(PortalPlaceholderBlock.instance)) blocks.add(pos);
                }
            }
        }
        return List.copyOf(blocks);
    }
}
