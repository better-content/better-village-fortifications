package com.bettercontent.villagewalls;

import com.bettercontent.villagewalls.command.VillageWallsCommands;
import com.bettercontent.villagewalls.config.VillageWallsConfig;
import com.bettercontent.villagewalls.config.WallStyleRegistry;
import com.bettercontent.villagewalls.world.AutoVillageWallBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VillageWalls.MOD_ID)
public class VillageWalls {
    public static final String MOD_ID = "village_walls";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public VillageWalls() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VillageWallsConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new VillageWallsCommands());
        MinecraftForge.EVENT_BUS.register(new WallStyleRegistry());
        MinecraftForge.EVENT_BUS.register(new AutoVillageWallBuilder());
    }
}
