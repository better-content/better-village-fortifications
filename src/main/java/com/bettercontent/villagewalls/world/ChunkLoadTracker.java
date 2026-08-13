package com.bettercontent.villagewalls.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;

final class ChunkLoadTracker {
    private ChunkLoadTracker() {
    }

    @FunctionalInterface
    interface ChunkPresence {
        boolean hasChunk(int chunkX, int chunkZ);
    }

    static boolean hasLoadedSearchArea(BlockPos origin, int searchRadius, ChunkPresence chunks) {
        return firstMissingSearchChunk(origin, searchRadius, chunks).isEmpty();
    }

    static Optional<ChunkPos> firstMissingSearchChunk(BlockPos origin, int searchRadius, ChunkPresence chunks) {
        int minChunkX = blockToChunk(origin.getX() - searchRadius);
        int maxChunkX = blockToChunk(origin.getX() + searchRadius);
        int minChunkZ = blockToChunk(origin.getZ() - searchRadius);
        int maxChunkZ = blockToChunk(origin.getZ() + searchRadius);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!chunks.hasChunk(chunkX, chunkZ)) {
                    return Optional.of(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        return Optional.empty();
    }

    private static int blockToChunk(int blockCoordinate) {
        return blockCoordinate >> 4;
    }
}
