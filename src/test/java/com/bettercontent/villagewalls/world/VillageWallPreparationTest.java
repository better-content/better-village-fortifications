package com.bettercontent.villagewalls.world;

import com.bettercontent.villagewalls.logic.GridPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWallPreparationTest {
    @Test
    void placementPreloadIncludesChunksBeyondTheSearchBoundary() {
        var preparation = new VillageWallGenerator.Preparation(
                VillageWallGenerator.Status.BUILT,
                Set.of(new GridPos(64, 0)),
                List.of(new GridPos(72, 0))
        );
        Set<ChunkPos> chunks = new VillageWallGenerator().placementChunks(preparation, 17);
        assertTrue(chunks.contains(new ChunkPos(5, 0)));
        assertTrue(chunks.contains(new ChunkPos(3, -2)));
    }
}
