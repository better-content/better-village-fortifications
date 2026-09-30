package com.bettercontent.bettervillagefortifications.config;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record WallStyle(
        ResourceLocation id,
        int thickness,
        int height,
        boolean walkable,
        int supportEvery,
        Palette foundation,
        Palette body,
        Palette support,
        Palette cap,
        Palette parapet,
        List<DetailRule> details
) {
    public WallStyle {
        details = List.copyOf(details);
    }

    public record Palette(List<BlockChoice> choices) {
        public Palette {
            choices = List.copyOf(choices);
        }

        public String fallbackBlock() {
            return choices.stream().filter(choice -> !choice.optional()).findFirst().orElseThrow().block();
        }
    }

    public record BlockChoice(String block, int weight, boolean optional) {
    }

    public record DetailRule(String block, int spacing, int salt, Side side, int verticalOffset, boolean optional) {
        public enum Side {
            INSIDE,
            OUTSIDE,
            BOTH
        }
    }
}
