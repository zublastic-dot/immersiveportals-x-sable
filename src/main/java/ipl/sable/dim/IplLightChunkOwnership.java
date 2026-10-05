package ipl.sable.dim;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;

/** Keeps lighting data in the world whose section indices and light engine own it. */
public final class IplLightChunkOwnership {
    private IplLightChunkOwnership() {}

    public static boolean belongsTo(BlockGetter owner, LightChunk chunk) {
        return !(chunk instanceof LevelChunk levelChunk) || select(owner, levelChunk.getLevel(), chunk) != null;
    }

    public static <T extends LightChunk> T forWorld(BlockGetter owner, T chunk) {
        // ProtoChunks do not carry a Level; leave generation's normal lookup untouched.
        if (chunk instanceof LevelChunk levelChunk) {
            return select(owner, levelChunk.getLevel(), chunk);
        }
        return chunk;
    }

    static <T> T select(Object owner, Object chunkOwner, T chunk) {
        // Equal heights (or even equal dimension keys) do not establish ownership.
        return owner == chunkOwner ? chunk : null;
    }
}
