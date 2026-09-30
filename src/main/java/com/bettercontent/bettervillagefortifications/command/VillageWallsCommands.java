package com.bettercontent.bettervillagefortifications.command;

import com.bettercontent.bettervillagefortifications.config.WallStyle;
import com.bettercontent.bettervillagefortifications.config.WallStyleRegistry;
import com.bettercontent.bettervillagefortifications.world.VillageWallGenerator;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class VillageWallsCommands {
    private final VillageWallGenerator generator = new VillageWallGenerator();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("bettervillagefortifications")
                .requires(source -> source.hasPermission(2));

        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("build")
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("style", StringArgumentType.string())
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("searchRadius", IntegerArgumentType.integer(32, 256))
                                .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("buffer", IntegerArgumentType.integer(2, 24))
                                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("maxDoors", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> executeBuild(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "style"),
                                                        IntegerArgumentType.getInteger(ctx, "searchRadius"),
                                                        IntegerArgumentType.getInteger(ctx, "buffer"),
                                                        IntegerArgumentType.getInteger(ctx, "maxDoors")
                                                )))))));

        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("styles")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Loaded styles: " + WallStyleRegistry.listStyleIds()), false);
                    return Command.SINGLE_SUCCESS;
                }));

        event.getDispatcher().register(root);
    }

    private int executeBuild(CommandSourceStack source, String styleIdRaw, int searchRadius, int buffer, int maxDoors) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player context required"));
            return 0;
        }
        ResourceLocation styleId = ResourceLocation.tryParse(styleIdRaw);
        if (styleId == null) {
            source.sendFailure(Component.literal("Invalid style id: " + styleIdRaw));
            return 0;
        }
        WallStyle style = WallStyleRegistry.getStyle(styleId).orElseGet(() ->
                WallStyleRegistry.getStyle(WallStyleRegistry.defaultStyleId()).orElse(null));
        if (style == null) {
            source.sendFailure(Component.literal("No wall styles loaded. Check datapacks and /reload."));
            return 0;
        }

        VillageWallGenerator.Result result = generator.generate(player.serverLevel(), player.blockPosition(), searchRadius, buffer, style, maxDoors);
        if (result.status() == VillageWallGenerator.Status.INCOMPLETE_SEARCH_AREA) {
            source.sendFailure(Component.literal("Cannot build yet: not all chunks in radius " + searchRadius + " are loaded."));
            return 0;
        }
        if (result.footprintPoints() == 0) {
            source.sendFailure(Component.literal("No village structure or POIs found in radius " + searchRadius));
            return 0;
        }
        if (result.perimeterPoints() < 4) {
            source.sendFailure(Component.literal("Village outline was too small to build a wall. Try a larger buffer."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "VillageWalls built. footprintPoints=" + result.footprintPoints()
                        + ", perimeterPoints=" + result.perimeterPoints()
                        + ", doorBlocks=" + result.doorsPlaced()),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
