package com.example.villagewalls;

import com.example.villagewalls.command.VillageWallsCommands;
import com.example.villagewalls.config.WallStyleRegistry;
import com.example.villagewalls.world.AutoVillageWallBuilder;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;

@Mod(VillageWalls.MOD_ID)
public class VillageWalls {
    public static final String MOD_ID = "villagewalls";

    public VillageWalls() {
        MinecraftForge.EVENT_BUS.register(new VillageWallsCommands());
        MinecraftForge.EVENT_BUS.register(new WallStyleRegistry());
        MinecraftForge.EVENT_BUS.register(new AutoVillageWallBuilder());
    }
}
