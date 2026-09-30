package com.bettercontent.bettervillagefortifications;

import com.bettercontent.bettervillagefortifications.command.VillageWallsCommands;
import com.bettercontent.bettervillagefortifications.config.VillageWallsConfig;
import com.bettercontent.bettervillagefortifications.config.WallStyleRegistry;
import com.bettercontent.bettervillagefortifications.world.AutoVillageWallBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VillageWalls.MOD_ID)
public class VillageWalls {
    public static final String MOD_ID = "better_village_fortifications";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public VillageWalls() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VillageWallsConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new VillageWallsCommands());
        MinecraftForge.EVENT_BUS.register(new WallStyleRegistry());
        MinecraftForge.EVENT_BUS.register(new AutoVillageWallBuilder());
    }
}
