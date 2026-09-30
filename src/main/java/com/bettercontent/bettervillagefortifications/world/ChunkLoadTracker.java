package com.bettercontent.bettervillagefortifications.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

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
        return searchChunks(origin, searchRadius).stream()
                .filter(chunk -> !chunks.hasChunk(chunk.x, chunk.z))
                .findFirst();
    }

    static List<ChunkPos> searchChunks(BlockPos origin, int searchRadius) {
        int minChunkX = blockToChunk(origin.getX() - searchRadius);
        int maxChunkX = blockToChunk(origin.getX() + searchRadius);
        int minChunkZ = blockToChunk(origin.getZ() - searchRadius);
        int maxChunkZ = blockToChunk(origin.getZ() + searchRadius);
        List<ChunkPos> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                result.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return result;
    }

    private static int blockToChunk(int blockCoordinate) {
        return blockCoordinate >> 4;
    }
}
