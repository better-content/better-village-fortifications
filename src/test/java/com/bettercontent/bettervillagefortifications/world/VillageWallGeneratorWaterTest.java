package com.bettercontent.bettervillagefortifications.world;

import com.bettercontent.bettervillagefortifications.logic.GridPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWallGeneratorWaterTest {
    @Test
    void placementIncludesChunksBetweenSparsePerimeterPoints() {
        var preparation = new VillageWallGenerator.Preparation(VillageWallGenerator.Status.BUILT,
                Set.of(), List.of(new GridPos(0, 0), new GridPos(64, 0),
                        new GridPos(64, 64), new GridPos(0, 64)));

        assertTrue(new VillageWallGenerator().placementChunks(preparation, 0).contains(new ChunkPos(2, 0)));
    }

    @Test
    void wallColumnCanGenerateThroughFourWaterBlocks() {
        assertTrue(VillageWallGenerator.shouldPlaceWallColumn(4));
    }

    @Test
    void wallColumnDoesNotGenerateOverWaterDeeperThanFourBlocks() {
        assertFalse(VillageWallGenerator.shouldPlaceWallColumn(5));
    }

    @Test
    void shallowWaterFoundationStillReachesOceanFloor() {
        assertEquals(62, VillageWallGenerator.underwaterColumnBaseY(64, 62));
    }

    @Test
    void oceanFloorAboveSampledSurfaceDoesNotRaiseFoundation() {
        assertEquals(64, VillageWallGenerator.underwaterColumnBaseY(64, 66));
    }
}
