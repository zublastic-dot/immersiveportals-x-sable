package ipl.sable.dim;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;

/** Keeps lighting data in the world whose section indices and light engine own it. */
public final class IplLightChunkOwnership {
    private IplLightChunkOwnership() {}

    public static boolean belongsTo(BlockGetter owner, int x, int z, LightChunk chunk) {
        return forWorld(owner, x, z, chunk) == chunk;
    }

    public static <T extends LightChunk> T forWorld(BlockGetter owner, int x, int z, T chunk) {
        if (chunk instanceof ChunkAccess access) {
            ChunkPos pos = access.getPos();
            // A render fallback can belong to this world yet stand in for another
            // coordinate. Light caches index by both the query and chunk.getPos().
            if (selectPosition(x, z, pos.x, pos.z, chunk instanceof EmptyLevelChunk, chunk) == null) {
                return null;
            }
        }
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

    static <T> T selectPosition(int x, int z, int actualX, int actualZ, boolean placeholder, T chunk) {
        // EmptyLevelChunk is a missing-chunk sentinel, not loaded empty terrain.
        return !placeholder && x == actualX && z == actualZ ? chunk : null;
    }
}
