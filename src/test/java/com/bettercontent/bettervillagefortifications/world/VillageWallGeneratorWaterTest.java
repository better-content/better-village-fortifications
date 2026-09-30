package com.bettercontent.bettervillagefortifications.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWallGeneratorWaterTest {
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
