package com.example.villagewalls.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallStyleRegistryTest {
    @AfterEach
    void tearDown() {
        WallStyleRegistry.replaceStylesForTest(Map.of());
    }

    @Test
    void parseStyleReadsFields() {
        JsonObject obj = new JsonObject();
        obj.addProperty("primary_block", "minecraft:cobblestone");
        obj.addProperty("accent_block", "minecraft:spruce_log");
        obj.addProperty("accent_every", 5);
        obj.addProperty("thickness", 2);
        obj.addProperty("height", 6);
        obj.addProperty("walkable", true);

        WallStyle style = WallStyleRegistry.parseStyle(new ResourceLocation("villagewalls", "test"), obj);
        assertEquals("minecraft:cobblestone", style.primaryBlock());
        assertEquals("minecraft:spruce_log", style.accentBlock());
        assertEquals(5, style.accentEvery());
        assertEquals(2, style.thickness());
        assertEquals(6, style.height());
        assertTrue(style.walkable());
    }

    @Test
    void parseStyleRejectsInvalidValues() {
        JsonObject obj = new JsonObject();
        obj.addProperty("primary_block", "minecraft:cobblestone");
        obj.addProperty("accent_block", "minecraft:spruce_log");
        obj.addProperty("accent_every", 0);
        obj.addProperty("thickness", 1);
        obj.addProperty("height", 3);

        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(new ResourceLocation("villagewalls", "bad"), obj));
    }

    @Test
    void listAndGetStylesWork() {
        ResourceLocation idA = new ResourceLocation("villagewalls", "b");
        ResourceLocation idB = new ResourceLocation("villagewalls", "a");
        WallStyle style = new WallStyle(idA, "minecraft:cobblestone", "minecraft:spruce_log", 4, 1, 3, false);
        WallStyle style2 = new WallStyle(idB, "minecraft:stone_bricks", "minecraft:stone_brick_wall", 4, 1, 3, false);
        WallStyleRegistry.replaceStylesForTest(Map.of(idA, style, idB, style2));

        assertTrue(WallStyleRegistry.getStyle(idA).isPresent());
        assertEquals("villagewalls:a, villagewalls:b", WallStyleRegistry.listStyleIds());
        assertEquals(new ResourceLocation("villagewalls", "cobble_spruce_thin"), WallStyleRegistry.defaultStyleId());
    }
}
