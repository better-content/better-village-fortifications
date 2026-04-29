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
        assertEquals(3, score);
    }
}
