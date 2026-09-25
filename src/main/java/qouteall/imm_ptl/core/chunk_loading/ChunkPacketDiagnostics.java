package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Capture only failing client packets and explicitly requested server snapshots. */
final class ChunkPacketDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ChunkPacketCaptureStore STORE =
        new ChunkPacketCaptureStore(Path.of("logs", "imm_ptl_chunk_packets"));
    private static final Set<String> CLIENT_ATTEMPTS = new HashSet<>();

    private ChunkPacketDiagnostics() {}

    static void clientFailure(LevelChunk chunk, FriendlyByteBuf buffer, int start, Exception error) {
        String key = chunk.getLevel().dimension().location() + "/" + chunk.getPos();
        synchronized (CLIENT_ATTEMPTS) {
            // Bound both memory and repeated failed writes. Existing error logs
            // remain unchanged after the diagnostic quota has been exhausted.
            if (CLIENT_ATTEMPTS.size() >= ChunkPacketCaptureStore.MAX_REPORTS || !CLIENT_ATTEMPTS.add(key)) {
                return;
            }
        }
        try {
            Map<String, Object> details = metadata(chunk, "client_decode_failure");
            details.put("error_type", error.getClass().getName());
            details.put("error_message", error.getMessage());
            Path report = STORE.capture(buffer, start, details);
            LOGGER.error("Chunk packet evidence saved to {}", report.toAbsolutePath());
        } catch (Exception captureError) {
            // A diagnostic failure must not replace or suppress the original error.
            LOGGER.warn("Could not capture failed chunk packet: {}", captureError.toString());
        }
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("imm_ptl_chunk_packet")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("chunkX", IntegerArgumentType.integer(-1875000, 1875000))
                .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-1875000, 1875000))
                    .executes(context -> snapshot(context.getSource(),
                        IntegerArgumentType.getInteger(context, "chunkX"),
                        IntegerArgumentType.getInteger(context, "chunkZ"))))));
    }

    private static int snapshot(CommandSourceStack source, int x, int z) {
        ServerLevel level = source.getLevel();
        // No tickets, synchronous loads or generation: inspect an already loaded chunk.
        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        if (chunk == null) {
            source.sendFailure(Component.literal("Chunk is not loaded; no load or generation was requested."));
            return 0;
        }
        try {
            FriendlyByteBuf buffer = new ClientboundLevelChunkPacketData(chunk).getReadBuffer();
            try {
                Path report = STORE.capture(buffer, buffer.readerIndex(),
                    metadata(chunk, "server_loaded_chunk_snapshot"));
                source.sendSuccess(() -> Component.literal("Chunk packet snapshot saved to " + report
                    + ". This is a fresh serialization, not proof of bytes previously sent."), false);
                return 1;
            } finally {
                buffer.release();
            }
        } catch (Exception error) {
            LOGGER.error("Could not capture chunk packet snapshot {} {}", level.dimension().location(), chunk.getPos(), error);
            source.sendFailure(Component.literal("Chunk packet snapshot failed; see the server log."));
            return 0;
        }
    }

    private static Map<String, Object> metadata(LevelChunk chunk, String kind) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("dimension", chunk.getLevel().dimension().location().toString());
        details.put("chunk_x", chunk.getPos().x);
        details.put("chunk_z", chunk.getPos().z);
        details.put("min_section_y", chunk.getMinSection());
        details.put("section_count", chunk.getSectionsCount());
        details.put("block_state_registry_size", Block.BLOCK_STATE_REGISTRY.size());
        details.put("biome_registry_size", chunk.getLevel().registryAccess().registryOrThrow(Registries.BIOME).size());
        details.put("note", "Section data only; no block entity NBT. Saved chunk integrity and packet decoding are separate checks.");
        return details;
    }
}
