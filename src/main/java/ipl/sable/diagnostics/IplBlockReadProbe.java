package ipl.sable.diagnostics;

import com.mojang.logging.LogUtils;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

/** One-coordinate operator probe of existing chunks. Never requests or generates a chunk. */
public final class IplBlockReadProbe {
    private static final Logger LOG = LogUtils.getLogger();
    private IplBlockReadProbe() {}

    public record ChunkFacts(String chunkClass, String level, String position, int minY,
                             int sectionIndex, int sectionCount, String sectionEmpty,
                             String sectionState) {}

    /** Reads only the supplied chunk and its existing section; no level/container lookup. */
    public static ChunkFacts describeChunk(LevelChunk chunk, BlockPos pos) {
        if (chunk == null) return new ChunkFacts("null", "null", "null", 0, -1, 0,
            "unavailable", "unavailable");
        int index = chunk.getSectionIndex(pos.getY());
        int count = chunk.getSections().length;
        boolean valid = index >= 0 && index < count;
        var section = valid ? chunk.getSection(index) : null;
        return new ChunkFacts(chunk.getClass().getName(), IplIgnitionTraceFacts.level(chunk.getLevel()),
            chunk.getPos().toString(), chunk.getMinBuildHeight(), index, count,
            section == null ? "out_of_range" : Boolean.toString(section.hasOnlyAir()),
            section == null ? "unavailable" : section.getBlockState(
                pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15).toString());
    }

    public static int run(CommandSourceStack source, BlockPos target) {
        BlockPos pos = target.immutable();
        ServerLevel previous = IplWorldFrameContext.current();
        try {
            ServerLevel parent = source.getLevel();
            var hosting = IplDimAgnostic.getHostingContainerFor(parent);
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            if (hosting == null || !hosting.inBounds(x, z)) {
                return reject(source, "No hosted plot at probe coordinate");
            }
            var plot = hosting.getPlot(x, z);
            var owner = plot == null ? null : plot.getSubLevel();
            if (owner == null || owner.isRemoved() || IplDimAgnostic.getServerParentLevel(owner) != parent) {
                return reject(source, "No live hosted plot owned by the command source dimension");
            }
            // getChunk on a SubLevelContainer only accesses its existing plot holders.
            LevelChunk direct = hosting.getChunk(new ChunkPos(x, z));
            if (direct == null || !(hosting.getLevel() instanceof ServerLevel hostingLevel)) {
                return reject(source, "Hosted plot chunk is not loaded");
            }
            IplWorldFrameContext.push(parent);
            // These are deliberately loaded-only chunk-source reads, not Level.getBlockState.
            // Actual Level routing is observed separately by the armed click's optional hooks.
            LevelChunk parentChunk = parent.getChunkSource().getChunkNow(x, z);
            LevelChunk hostingChunk = hostingLevel.getChunkSource().getChunkNow(x, z);
            String parentState = logRead("parent_chunk_source", parent, pos, parentChunk, direct);
            String hostingState = logRead("hosting_chunk_source", hostingLevel, pos, hostingChunk, direct);
            String directState = logRead("hosted_plot_chunk", hostingLevel, pos, direct, direct);
            source.sendSuccess(() -> Component.literal("Block probe loaded_only=true at " + pos.toShortString()
                + ": parent=" + parentState + " hosting=" + hostingState + " plot=" + directState
                + "; chunk/section details logged. Level.getBlockState hooks remain separately unproven."), false);
            return 1;
        } catch (RuntimeException failure) {
            LOG.info("[IPL-BLOCK-PROBE] loaded_only=true pos={} unavailable={}",
                pos.toShortString(), failure.getClass().getSimpleName());
            return reject(source, "Block probe unavailable: " + failure.getClass().getSimpleName());
        } finally {
            // Plot resolution may arm an enclosing deferred frame; do not retain that change.
            IplWorldFrameContext.pop(previous);
        }
    }

    private static String logRead(String path, ServerLevel caller, BlockPos pos,
                                  LevelChunk chunk, LevelChunk direct) {
        String state = chunk == null ? "unavailable" : chunk.getBlockState(pos).toString();
        ChunkFacts facts = describeChunk(chunk, pos);
        LOG.info("[IPL-BLOCK-PROBE] loaded_only=true path={} lookup_level={} pos={} state={} "
                + "same_as_plot_chunk={} chunk_class={} chunk_level={} chunk_pos={} min_y={} "
                + "section_index={} section_count={} section_empty={} section_state={}",
            path, IplIgnitionTraceFacts.level(caller), pos.toShortString(), state, chunk == direct,
            facts.chunkClass(), facts.level(), facts.position(), facts.minY(), facts.sectionIndex(),
            facts.sectionCount(), facts.sectionEmpty(), facts.sectionState());
        return state;
    }

    private static int reject(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
