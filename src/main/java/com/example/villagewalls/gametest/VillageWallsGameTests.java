package com.example.villagewalls.gametest;

import com.example.villagewalls.VillageWalls;
import com.example.villagewalls.config.WallStyle;
import com.example.villagewalls.config.WallStyleRegistry;
import com.example.villagewalls.world.VillageWallGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillageWalls.MOD_ID)
@PrefixGameTestTemplate(false)
@Mod.EventBusSubscriber(modid = VillageWalls.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class VillageWallsGameTests {
    public VillageWallsGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(VillageWallsGameTests.class);
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "villagewalls_data", timeoutTicks = 80)
    public static void bundledWallStylesLoadOnServer(GameTestHelper helper) {
        ResourceLocation defaultId = WallStyleRegistry.defaultStyleId();
        helper.assertTrue(WallStyleRegistry.getStyle(defaultId).isPresent(), "default wall style should be loaded: " + defaultId);
        helper.assertTrue(
                WallStyleRegistry.listStyleIds().contains("villagewalls:rampart_stonebrick"),
                "bundled rampart style should be listed"
        );
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "villagewalls_generator", timeoutTicks = 120)
    public static void generatorReportsEmptyResultWhenNoVillageFootprintExists(GameTestHelper helper) {
        WallStyle style = WallStyleRegistry.getStyle(WallStyleRegistry.defaultStyleId()).orElseGet(() ->
                new WallStyle(
                        WallStyleRegistry.defaultStyleId(),
                        "minecraft:cobblestone",
                        "minecraft:spruce_log",
                        4,
                        1,
                        3,
                        false
                )
        );

        VillageWallGenerator.Result result = new VillageWallGenerator().generate(
                helper.getLevel(),
                helper.absolutePos(new BlockPos(2, 2, 2)),
                32,
                6,
                style,
                2
        );

        helper.assertTrue(result.footprintPoints() == 0, "empty test world should not produce village footprint points");
        helper.assertTrue(result.perimeterPoints() == 0, "empty test world should not produce perimeter points");
        helper.assertTrue(result.doorsPlaced() == 0, "empty test world should not place doors");
        helper.succeed();
    }
}
