package com.example.villagewalls.config;

import net.minecraft.resources.ResourceLocation;

public record WallStyle(
        ResourceLocation id,
        String primaryBlock,
        String accentBlock,
        int accentEvery,
        int thickness,
        int height,
        boolean walkable
) {
}
