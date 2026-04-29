package com.example.villagewalls.command;

import com.example.villagewalls.config.WallStyle;
import com.example.villagewalls.config.WallStyleRegistry;
import com.example.villagewalls.world.VillageWallGenerator;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class VillageWallsCommands {
    private final VillageWallGenerator generator = new VillageWallGenerator();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("villagewalls")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("build")
                .then(Commands.argument("style", StringArgumentType.string())
                        .then(Commands.argument("searchRadius", IntegerArgumentType.integer(32, 256))
                                .then(Commands.argument("buffer", IntegerArgumentType.integer(2, 24))
                                        .then(Commands.argument("maxDoors", IntegerArgumentType.integer(1, 16))
                                                .executes(ctx -> executeBuild(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "style"),
                                                        IntegerArgumentType.getInteger(ctx, "searchRadius"),
                                                        IntegerArgumentType.getInteger(ctx, "buffer"),
                                                        IntegerArgumentType.getInteger(ctx, "maxDoors")
                                                )))))));

        root.then(Commands.literal("styles")
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
        if (result.villagePoints() == 0) {
            source.sendFailure(Component.literal("No villagers found in radius " + searchRadius));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "VillageWalls built. villagers=" + result.villagePoints()
                        + ", perimeterPoints=" + result.perimeterPoints()
                        + ", doorBlocks=" + result.doorsPlaced()),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
