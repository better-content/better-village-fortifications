package com.example.villagewalls.world;

@FunctionalInterface
public interface TerrainSampler {
    int surfaceY(int x, int z);
}
