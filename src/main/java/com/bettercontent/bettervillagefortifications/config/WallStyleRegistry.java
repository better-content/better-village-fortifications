package com.bettercontent.bettervillagefortifications.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WallStyleRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<ResourceLocation, WallStyle> STYLES = new HashMap<>();
    private static List<StyleSelector> selectors = List.of();

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    public static Optional<WallStyle> getStyle(ResourceLocation id) {
        return Optional.ofNullable(STYLES.get(id));
    }

    public static Optional<WallStyle> selectForBiome(ResourceLocation biomeId) {
        String value = biomeId.toString();
        return selectors.stream()
                .filter(selector -> selector.biomePatterns().stream().anyMatch(value::contains))
                .map(StyleSelector::style)
                .map(STYLES::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .or(() -> getStyle(defaultStyleId()));
    }

    public static ResourceLocation defaultStyleId() {
        return new ResourceLocation("better_village_fortifications", "temperate");
    }

    public static ResourceLocation rampartStyleId() {
        return new ResourceLocation("better_village_fortifications", "rampart_stonebrick");
    }

    public static String listStyleIds() {
        return STYLES.keySet().stream().map(ResourceLocation::toString).sorted().reduce((a, b) -> a + ", " + b).orElse("<none>");
    }

    static void replaceStylesForTest(Map<ResourceLocation, WallStyle> styles) {
        STYLES.clear();
        STYLES.putAll(styles);
        selectors = List.of();
    }

    static void replaceSelectorsForTest(List<StyleSelector> replacements) {
        selectors = replacements.stream().sorted(Comparator.comparingInt(StyleSelector::priority).reversed()).toList();
    }

    static WallStyle parseStyle(ResourceLocation id, JsonObject obj) {
        int thickness = GsonHelper.getAsInt(obj, "thickness", 1);
        int height = GsonHelper.getAsInt(obj, "height", 3);
        boolean walkable = GsonHelper.getAsBoolean(obj, "walkable", false);
        int supportEvery = GsonHelper.getAsInt(obj, "support_every", 5);
        if (thickness < 1 || height < 2 || supportEvery < 2) {
            throw new JsonParseException("Invalid wall geometry values for " + id);
        }
        JsonObject palettes = GsonHelper.getAsJsonObject(obj, "palettes");
        WallStyle.Palette foundation = parsePalette(id, "foundation", palettes);
        WallStyle.Palette body = parsePalette(id, "body", palettes);
        WallStyle.Palette support = parsePalette(id, "support", palettes);
        WallStyle.Palette cap = parsePalette(id, "cap", palettes);
        WallStyle.Palette parapet = palettes.has("parapet") ? parsePalette(id, "parapet", palettes) : cap;
        List<WallStyle.DetailRule> details = parseDetails(id, obj);
        return new WallStyle(id, thickness, height, walkable, supportEvery, foundation, body, support, cap, parapet, details);
    }

    private static WallStyle.Palette parsePalette(ResourceLocation id, String name, JsonObject palettes) {
        JsonArray array = GsonHelper.getAsJsonArray(palettes, name);
        List<WallStyle.BlockChoice> choices = new ArrayList<>();
        boolean hasRequiredVanillaFallback = false;
        for (JsonElement element : array) {
            JsonObject choice = GsonHelper.convertToJsonObject(element, name + " palette entry");
            String block = GsonHelper.getAsString(choice, "block");
            int weight = GsonHelper.getAsInt(choice, "weight", 1);
            boolean optional = GsonHelper.getAsBoolean(choice, "optional", false);
            ResourceLocation blockId = ResourceLocation.tryParse(blockStateId(block));
            if (blockId == null || weight < 1) {
                throw new JsonParseException("Invalid " + name + " palette entry for " + id);
            }
            hasRequiredVanillaFallback |= !optional && blockId.getNamespace().equals("minecraft");
            choices.add(new WallStyle.BlockChoice(block, weight, optional));
        }
        if (choices.isEmpty() || !hasRequiredVanillaFallback) {
            throw new JsonParseException(name + " palette for " + id + " requires a non-optional minecraft fallback");
        }
        return new WallStyle.Palette(choices);
    }

    private static List<WallStyle.DetailRule> parseDetails(ResourceLocation id, JsonObject obj) {
        if (!obj.has("details")) {
            return List.of();
        }
        List<WallStyle.DetailRule> details = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(obj, "details")) {
            JsonObject detail = GsonHelper.convertToJsonObject(element, "detail rule");
            String block = GsonHelper.getAsString(detail, "block");
            int spacing = GsonHelper.getAsInt(detail, "spacing");
            int salt = GsonHelper.getAsInt(detail, "salt", details.size() + 1);
            int verticalOffset = GsonHelper.getAsInt(detail, "vertical_offset", 1);
            boolean optional = GsonHelper.getAsBoolean(detail, "optional", false);
            String sideName = GsonHelper.getAsString(detail, "side", "inside").toUpperCase(java.util.Locale.ROOT);
            if (ResourceLocation.tryParse(blockStateId(block)) == null || spacing < 6 || verticalOffset < 1) {
                throw new JsonParseException("Invalid detail rule for " + id);
            }
            WallStyle.DetailRule.Side side;
            try {
                side = WallStyle.DetailRule.Side.valueOf(sideName);
            } catch (IllegalArgumentException ex) {
                throw new JsonParseException("Invalid detail side for " + id + ": " + sideName, ex);
            }
            details.add(new WallStyle.DetailRule(block, spacing, salt, side, verticalOffset, optional));
        }
        return List.copyOf(details);
    }

    static List<StyleSelector> parseSelectors(JsonObject obj) {
        List<StyleSelector> parsed = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(obj, "selectors")) {
            JsonObject selector = GsonHelper.convertToJsonObject(element, "selector");
            ResourceLocation style = new ResourceLocation(GsonHelper.getAsString(selector, "style"));
            List<String> patterns = new ArrayList<>();
            GsonHelper.getAsJsonArray(selector, "biome_patterns").forEach(pattern -> patterns.add(pattern.getAsString()));
            if (patterns.isEmpty() || patterns.stream().anyMatch(String::isBlank)) {
                throw new JsonParseException("Selector for " + style + " requires biome patterns");
            }
            parsed.add(new StyleSelector(style, List.copyOf(patterns), GsonHelper.getAsInt(selector, "priority", 0)));
        }
        return parsed.stream().sorted(Comparator.comparingInt(StyleSelector::priority).reversed()).toList();
    }

    static String blockStateId(String blockState) {
        int properties = blockState.indexOf('[');
        return properties < 0 ? blockState : blockState.substring(0, properties);
    }

    static void validateStyleForTest(WallStyle style, java.util.function.Function<String, BlockValidation> validator) {
        validateBlocks(style, validator);
    }

    record StyleSelector(ResourceLocation style, List<String> biomePatterns, int priority) {
    }

    enum BlockValidation {
        VALID,
        MISSING,
        INVALID_STATE
    }

    private record Prepared(Map<ResourceLocation, WallStyle> styles, List<StyleSelector> selectors) {
    }

    private static class Loader extends SimplePreparableReloadListener<Prepared> {
        @Override
        protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
            Map<ResourceLocation, WallStyle> loaded = new HashMap<>();
            for (Map.Entry<ResourceLocation, Resource> entry : resourceManager.listResources("wall_styles", rl -> rl.getPath().endsWith(".json")).entrySet()) {
                ResourceLocation fileId = entry.getKey();
                String path = fileId.getPath();
                ResourceLocation styleId = new ResourceLocation(fileId.getNamespace(), path.substring("wall_styles/".length(), path.length() - 5));
                loaded.put(styleId, read(entry.getValue(), obj -> parseStyle(styleId, obj), "wall style " + fileId));
            }
            List<StyleSelector> loadedSelectors = new ArrayList<>();
            for (Map.Entry<ResourceLocation, Resource> entry : resourceManager.listResources("wall_style_selectors", rl -> rl.getPath().endsWith(".json")).entrySet()) {
                loadedSelectors.addAll(read(entry.getValue(), WallStyleRegistry::parseSelectors, "wall style selectors " + entry.getKey()));
            }
            loadedSelectors.sort(Comparator.comparingInt(StyleSelector::priority).reversed());
            return new Prepared(Map.copyOf(loaded), List.copyOf(loadedSelectors));
        }

        private static <T> T read(Resource resource, java.util.function.Function<JsonObject, T> parser, String description) {
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                return parser.apply(GSON.fromJson(reader, JsonObject.class));
            } catch (IOException | JsonParseException ex) {
                throw new RuntimeException("Failed to parse " + description, ex);
            }
        }

        @Override
        protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
            prepared.styles().values().forEach(WallStyleRegistry::validateRequiredBlocks);
            prepared.selectors().forEach(selector -> {
                if (!prepared.styles().containsKey(selector.style())) {
                    throw new JsonParseException("Wall style selector references missing style " + selector.style());
                }
            });
            if (!prepared.styles().containsKey(defaultStyleId()) || !prepared.styles().containsKey(rampartStyleId())) {
                throw new JsonParseException("Wall styles must provide " + defaultStyleId() + " and " + rampartStyleId());
            }
            STYLES.clear();
            STYLES.putAll(prepared.styles());
            selectors = prepared.selectors();
        }
    }

    private static void validateRequiredBlocks(WallStyle style) {
        validateBlocks(style, WallStyleRegistry::validateRegisteredBlockState);
    }

    private static void validateBlocks(WallStyle style, java.util.function.Function<String, BlockValidation> validator) {
        List<WallStyle.Palette> palettes = List.of(style.foundation(), style.body(), style.support(), style.cap(), style.parapet());
        for (WallStyle.Palette palette : palettes) {
            for (WallStyle.BlockChoice choice : palette.choices()) {
                BlockValidation validation = validator.apply(choice.block());
                if (!choice.optional() && validation == BlockValidation.MISSING) {
                    throw new JsonParseException("Required block " + choice.block() + " is not registered for " + style.id());
                }
                if (validation == BlockValidation.INVALID_STATE) {
                    throw new JsonParseException("Invalid block-state property in " + choice.block() + " for " + style.id());
                }
            }
        }
        for (WallStyle.DetailRule detail : style.details()) {
            BlockValidation validation = validator.apply(detail.block());
            if (!detail.optional() && validation == BlockValidation.MISSING) {
                throw new JsonParseException("Required detail block " + detail.block() + " is not registered for " + style.id());
            }
            if (validation == BlockValidation.INVALID_STATE) {
                throw new JsonParseException("Invalid block-state property in " + detail.block() + " for " + style.id());
            }
        }
    }

    private static BlockValidation validateRegisteredBlockState(String specification) {
        ResourceLocation blockId = new ResourceLocation(blockStateId(specification));
        if (!net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(blockId)) {
            return BlockValidation.MISSING;
        }
        Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(blockId);
        int open = specification.indexOf('[');
        if (open < 0) {
            return BlockValidation.VALID;
        }
        if (!specification.endsWith("]")) {
            return BlockValidation.INVALID_STATE;
        }
        for (String assignment : specification.substring(open + 1, specification.length() - 1).split(",")) {
            String[] pair = assignment.split("=", 2);
            Property<?> property = pair.length == 2 ? block.getStateDefinition().getProperty(pair[0]) : null;
            if (property == null || property.getValue(pair[1]).isEmpty()) {
                return BlockValidation.INVALID_STATE;
            }
        }
        return BlockValidation.VALID;
    }
}
