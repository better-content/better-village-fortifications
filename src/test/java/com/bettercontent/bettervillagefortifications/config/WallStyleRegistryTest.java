package com.bettercontent.bettervillagefortifications.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallStyleRegistryTest {
    private static final String VALID_STYLE = """
            {
              "thickness": 2,
              "height": 6,
              "walkable": true,
              "support_every": 5,
              "palettes": {
                "foundation": [{"block":"minecraft:cobblestone","weight":3}],
                "body": [{"block":"minecraft:stone_bricks","weight":5},{"block":"quark:limestone_bricks","weight":2,"optional":true}],
                "support": [{"block":"minecraft:spruce_log[axis=y]"}],
                "cap": [{"block":"minecraft:stone_bricks"}]
              },
              "details": [{"block":"supplementaries:sconce","spacing":17,"salt":9,"side":"outside","vertical_offset":2,"optional":true}]
            }
            """;

    @AfterEach
    void tearDown() {
        WallStyleRegistry.replaceStylesForTest(Map.of());
    }

    @Test
    void parseStyleReadsPalettesGeometryAndDetails() {
        WallStyle style = WallStyleRegistry.parseStyle(id("test"), json(VALID_STYLE));

        assertEquals(2, style.thickness());
        assertEquals(6, style.height());
        assertTrue(style.walkable());
        assertEquals(5, style.supportEvery());
        assertEquals("minecraft:cobblestone", style.foundation().fallbackBlock());
        assertEquals(2, style.body().choices().size());
        assertTrue(style.body().choices().get(1).optional());
        assertEquals(WallStyle.DetailRule.Side.OUTSIDE, style.details().get(0).side());
    }

    @Test
    void parseStyleRejectsInvalidGeometryAndUnsafeDetails() {
        JsonObject badGeometry = json(VALID_STYLE);
        badGeometry.addProperty("support_every", 1);
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("bad_geometry"), badGeometry));

        JsonObject badDetail = json(VALID_STYLE);
        badDetail.getAsJsonArray("details").get(0).getAsJsonObject().addProperty("spacing", 5);
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("bad_detail"), badDetail));

        JsonObject badSide = json(VALID_STYLE);
        badSide.getAsJsonArray("details").get(0).getAsJsonObject().addProperty("side", "upside_down");
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("bad_side"), badSide));

        JsonObject invalidWeight = json(VALID_STYLE);
        invalidWeight.getAsJsonObject("palettes").getAsJsonArray("body").get(0).getAsJsonObject().addProperty("weight", 0);
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("bad_weight"), invalidWeight));
    }

    @Test
    void styleWithoutDetailsUsesEmptyRuleList() {
        JsonObject object = json(VALID_STYLE);
        object.remove("details");
        assertTrue(WallStyleRegistry.parseStyle(id("plain"), object).details().isEmpty());
    }

    @Test
    void parseStyleRequiresVanillaFallbackInEveryPalette() {
        JsonObject empty = json(VALID_STYLE);
        empty.getAsJsonObject("palettes").add("cap", json("{\"entries\":[]}").getAsJsonArray("entries"));
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("empty"), empty));

        JsonObject optionalOnly = json(VALID_STYLE);
        optionalOnly.getAsJsonObject("palettes").add("cap", json("{\"entries\":[{\"block\":\"quark:limestone\",\"optional\":true}]}").getAsJsonArray("entries"));
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseStyle(id("optional_only"), optionalOnly));
    }

    @Test
    void selectorsUsePriorityAndFallBackToDefault() {
        WallStyle temperate = WallStyleRegistry.parseStyle(WallStyleRegistry.defaultStyleId(), json(VALID_STYLE));
        WallStyle snowy = WallStyleRegistry.parseStyle(id("snowy"), json(VALID_STYLE));
        WallStyleRegistry.replaceStylesForTest(Map.of(temperate.id(), temperate, snowy.id(), snowy));
        WallStyleRegistry.replaceSelectorsForTest(List.of(
                new WallStyleRegistry.StyleSelector(temperate.id(), List.of("minecraft:"), 1),
                new WallStyleRegistry.StyleSelector(snowy.id(), List.of("snowy"), 10)
        ));

        assertEquals(snowy.id(), WallStyleRegistry.selectForBiome(new ResourceLocation("minecraft", "snowy_plains")).orElseThrow().id());
        assertEquals(temperate.id(), WallStyleRegistry.selectForBiome(new ResourceLocation("modded", "unknown_grove")).orElseThrow().id());
    }

    @Test
    void parseSelectorsAndRegistryHelpersWork() {
        List<WallStyleRegistry.StyleSelector> parsed = WallStyleRegistry.parseSelectors(json("""
                {"selectors":[
                  {"style":"better_village_fortifications:taiga","priority":2,"biome_patterns":["pine"]},
                  {"style":"better_village_fortifications:snowy","priority":9,"biome_patterns":["snowy"]}
                ]}
                """));
        assertEquals("snowy", parsed.get(0).biomePatterns().get(0));
        assertEquals("minecraft:spruce_log", WallStyleRegistry.blockStateId("minecraft:spruce_log[axis=y]"));
        assertEquals("minecraft:stone", WallStyleRegistry.blockStateId("minecraft:stone"));

        WallStyle style = WallStyleRegistry.parseStyle(id("b"), json(VALID_STYLE));
        WallStyle style2 = WallStyleRegistry.parseStyle(id("a"), json(VALID_STYLE));
        WallStyleRegistry.replaceStylesForTest(Map.of(style.id(), style, style2.id(), style2));
        assertEquals("better_village_fortifications:a, better_village_fortifications:b", WallStyleRegistry.listStyleIds());
        assertTrue(WallStyleRegistry.getStyle(style.id()).isPresent());
        assertEquals(id("rampart_stonebrick"), WallStyleRegistry.rampartStyleId());

        assertThrows(JsonParseException.class, () -> WallStyleRegistry.parseSelectors(json("""
                {"selectors":[{"style":"better_village_fortifications:taiga","biome_patterns":[]}]}
                """)));
    }

    @Test
    void runtimeValidationChecksRequiredBlocksAndProperties() {
        JsonObject validObject = json(VALID_STYLE);
        validObject.getAsJsonObject("palettes").getAsJsonArray("support").get(0).getAsJsonObject().addProperty("block", "minecraft:spruce_log");
        WallStyle valid = WallStyleRegistry.parseStyle(id("valid"), validObject);
        WallStyleRegistry.validateStyleForTest(valid, WallStyleRegistryTest::validateBlock);

        JsonObject missing = json(VALID_STYLE);
        missing.getAsJsonObject("palettes").getAsJsonArray("body").get(0).getAsJsonObject().addProperty("block", "minecraft:not_a_real_block");
        WallStyle missingStyle = WallStyleRegistry.parseStyle(id("missing"), missing);
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.validateStyleForTest(missingStyle, WallStyleRegistryTest::validateBlock));

        JsonObject badProperty = json(VALID_STYLE);
        badProperty.getAsJsonObject("palettes").getAsJsonArray("support").get(0).getAsJsonObject().addProperty("block", "minecraft:spruce_log[axis=sideways]");
        WallStyle badPropertyStyle = WallStyleRegistry.parseStyle(id("bad_property"), badProperty);
        assertThrows(JsonParseException.class, () -> WallStyleRegistry.validateStyleForTest(badPropertyStyle, WallStyleRegistryTest::validateBlock));
    }

    private static WallStyleRegistry.BlockValidation validateBlock(String specification) {
        if (specification.contains("not_a_real_block")) {
            return WallStyleRegistry.BlockValidation.MISSING;
        }
        if (specification.contains("sideways")) {
            return WallStyleRegistry.BlockValidation.INVALID_STATE;
        }
        return specification.startsWith("quark:") || specification.startsWith("supplementaries:")
                ? WallStyleRegistry.BlockValidation.MISSING
                : WallStyleRegistry.BlockValidation.VALID;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("better_village_fortifications", path);
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
