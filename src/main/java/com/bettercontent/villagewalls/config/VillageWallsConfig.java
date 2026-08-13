package com.bettercontent.villagewalls.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class VillageWallsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue AUTOMATIC_WALLS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTOMATIC_WALL_CHANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("automaticWalls");
        AUTOMATIC_WALLS_ENABLED = builder
                .comment("Whether village walls can be generated automatically when village structures load.")
                .define("enabled", true);
        AUTOMATIC_WALL_CHANCE = builder
                .comment("Chance from 0.0 to 1.0 that an automatically detected village gets walls. A skipped village is remembered so it is not rerolled on every chunk load.")
                .defineInRange("spawnChance", 0.5D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private VillageWallsConfig() {
    }
}
