package com.example.villagewalls.logic;

import com.example.villagewalls.world.TerrainSampler;
import com.example.villagewalls.world.VillageWallGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWallGeneratorTest {
    @Test
    void rasterLineIsContinuous() {
        List<GridPos> line = VillageWallGenerator.rasterLine(new GridPos(0, 0), new GridPos(4, 2));
        assertEquals(new GridPos(0, 0), line.get(0));
        assertEquals(new GridPos(4, 2), line.get(line.size() - 1));
        assertTrue(line.size() >= 5);
    }

    @Test
    void segmentFlatnessIsHeightRange() {
        VillageWallGenerator generator = new VillageWallGenerator();
        TerrainSampler sampler = (x, z) -> x + z;
        int score = generator.segmentFlatnessScore(new GridPos(0, 0), new GridPos(3, 0), sampler);
        assertEquals(112, score);
    }

    @Test
    void segmentFlatnessPenalizesGateAreaSpikes() {
        VillageWallGenerator generator = new VillageWallGenerator();
        TerrainSampler flat = (x, z) -> 64;
        TerrainSampler spiky = (x, z) -> x == 4 && Math.abs(z) <= 1 ? 72 : 64;

        int flatScore = generator.segmentFlatnessScore(new GridPos(0, 0), new GridPos(8, 0), flat);
        int spikyScore = generator.segmentFlatnessScore(new GridPos(0, 0), new GridPos(8, 0), spiky);

        assertTrue(spikyScore > flatScore);
    }

    @Test
    void medianFilterRemovesSingleTopSpike() {
        VillageWallGenerator generator = new VillageWallGenerator();
        List<Integer> filtered = generator.medianFilter(List.of(67, 67, 80, 67, 67), 2);

        assertEquals(List.of(67, 67, 67, 67, 67), filtered);
    }

    @Test
    void clampStepDeltasLimitsAdjacentTopChanges() {
        VillageWallGenerator generator = new VillageWallGenerator();
        List<Integer> clamped = generator.clampStepDeltas(List.of(64, 70, 70, 65), 1);

        for (int i = 1; i < clamped.size(); i++) {
            assertTrue(Math.abs(clamped.get(i) - clamped.get(i - 1)) <= 1);
        }
    }

    @Test
    void topProfilePreservesFlatTerrain() {
        VillageWallGenerator generator = new VillageWallGenerator();
        List<GridPos> line = VillageWallGenerator.rasterLine(new GridPos(0, 0), new GridPos(6, 0));
        List<Integer> tops = generator.computeSegmentTopProfile(line, (x, z) -> 64, 3, 2, 1);

        assertEquals(List.of(66, 66, 66, 66, 66, 66, 66), tops);
    }

    @Test
    void enclosureTopUsesUpperTerrainBandForConsistentWallTop() {
        VillageWallGenerator generator = new VillageWallGenerator();
        List<GridPos> perimeter = List.of(
                new GridPos(0, 0),
                new GridPos(4, 0),
                new GridPos(4, 4),
                new GridPos(0, 4)
        );
        TerrainSampler sampler = (x, z) -> x >= 3 ? 70 : 64;

        assertEquals(72, generator.enclosureTopY(perimeter, sampler, 3));
    }
}
