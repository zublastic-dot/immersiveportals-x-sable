package ipl.sable.dim;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Rejects a plot transfer before placement if its destination cannot store every block. */
public final class IplPlotCopyHeight {
    private IplPlotCopyHeight() {}

    public static void requireFits(LevelHeightAccessor destinationStorage, Iterable<LevelChunk> sourceChunks) {
        // Snapshot these bounds once. The caller resolves hosting storage through
        // IplChunkStorageHeight, never through the temporarily routed parent frame.
        int minY = destinationStorage.getMinBuildHeight();
        int maxY = destinationStorage.getMaxBuildHeight();
        for (LevelChunk chunk : sourceChunks) {
            LevelChunkSection[] sections = chunk.getSections();
            if (sections.length != chunk.getSectionsCount()) {
                throw new IllegalStateException("Source plot section array does not match its storage height; "
                    + "refusing a partial plot copy");
            }
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) continue;
                long baseY = (long) chunk.getSectionYFromSectionIndex(index) << 4;
                if (baseY >= minY && baseY + 15 < maxY) continue;
                for (int y = 0; y < 16; y++) {
                    long worldY = baseY + y;
                    if (worldY >= minY && worldY < maxY) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (!section.getBlockState(x, y, z).isAir()) {
                                throw new IllegalStateException("Source plot block at Y=" + worldY
                                    + " is outside destination storage [" + minY + ", " + maxY
                                    + "); refusing a partial plot copy");
                            }
                        }
                    }
                }
            }
        }
    }
}
