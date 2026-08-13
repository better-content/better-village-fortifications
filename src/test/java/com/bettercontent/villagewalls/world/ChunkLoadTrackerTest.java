package com.bettercontent.villagewalls.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLoadTrackerTest {
    @Test
    void acceptsFullyLoadedPositiveSearchArea() {
        Set<ChunkPos> loaded = loadedRectangle(-1, -1, 2, 2);

        boolean ready = ChunkLoadTracker.hasLoadedSearchArea(
                new BlockPos(8, 64, 8),
                24,
                (chunkX, chunkZ) -> loaded.contains(new ChunkPos(chunkX, chunkZ))
        );

        assertTrue(ready);
    }

    @Test
    void rejectsWhenAnyChunkInSearchAreaIsMissing() {
        Set<ChunkPos> loaded = loadedRectangle(-1, -1, 2, 2);
        loaded.remove(new ChunkPos(1, 0));

        boolean ready = ChunkLoadTracker.hasLoadedSearchArea(
                new BlockPos(8, 64, 8),
                24,
                (chunkX, chunkZ) -> loaded.contains(new ChunkPos(chunkX, chunkZ))
        );

        assertFalse(ready);
    }

    @Test
    void reportsFirstMissingChunkInScanOrder() {
        Set<ChunkPos> loaded = loadedRectangle(-1, -1, 2, 2);
        loaded.remove(new ChunkPos(-1, 1));
        loaded.remove(new ChunkPos(0, -1));

        ChunkPos missing = ChunkLoadTracker.firstMissingSearchChunk(
                new BlockPos(8, 64, 8),
                24,
                (chunkX, chunkZ) -> loaded.contains(new ChunkPos(chunkX, chunkZ))
        ).orElseThrow();

        assertEquals(new ChunkPos(-1, 1), missing);
    }

    @Test
    void usesFloorChunkCoordinatesForNegativeBlocks() {
        Set<ChunkPos> loaded = loadedRectangle(-3, -3, 1, 1);

        boolean ready = ChunkLoadTracker.hasLoadedSearchArea(
                new BlockPos(-1, 64, -1),
                32,
                (chunkX, chunkZ) -> loaded.contains(new ChunkPos(chunkX, chunkZ))
        );

        assertTrue(ready);
    }

    private static Set<ChunkPos> loadedRectangle(int minX, int minZ, int maxX, int maxZ) {
        Set<ChunkPos> loaded = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                loaded.add(new ChunkPos(x, z));
            }
        }
        return loaded;
    }
}
