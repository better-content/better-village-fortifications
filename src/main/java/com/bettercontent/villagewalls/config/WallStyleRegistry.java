package com.bettercontent.villagewalls.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class WallStyleRegistry {
    private static final Gson GSON = new Gson();
    private static final Map<ResourceLocation, WallStyle> STYLES = new HashMap<>();

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    public static Optional<WallStyle> getStyle(ResourceLocation id) {
        return Optional.ofNullable(STYLES.get(id));
    }

    public static ResourceLocation defaultStyleId() {
        return new ResourceLocation("village_walls", "cobble_spruce_thin");
    }

    public static String listStyleIds() {
        return STYLES.keySet().stream().map(ResourceLocation::toString).sorted().reduce((a, b) -> a + ", " + b).orElse("<none>");
    }

    static void replaceStylesForTest(Map<ResourceLocation, WallStyle> styles) {
        STYLES.clear();
        STYLES.putAll(styles);
    }

    static WallStyle parseStyle(ResourceLocation id, JsonObject obj) {
        String primary = GsonHelper.getAsString(obj, "primary_block");
        String accent = GsonHelper.getAsString(obj, "accent_block");
        int accentEvery = GsonHelper.getAsInt(obj, "accent_every", 4);
        int thickness = GsonHelper.getAsInt(obj, "thickness", 1);
        int height = GsonHelper.getAsInt(obj, "height", 3);
        boolean walkable = GsonHelper.getAsBoolean(obj, "walkable", false);
        if (accentEvery < 1 || thickness < 1 || height < 2) {
            throw new JsonParseException("Invalid wall style values for " + id);
        }
        return new WallStyle(id, primary, accent, accentEvery, thickness, height, walkable);
    }

    private static class Loader extends SimplePreparableReloadListener<Map<ResourceLocation, WallStyle>> {
        @Override
        protected Map<ResourceLocation, WallStyle> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
            Map<ResourceLocation, WallStyle> loaded = new HashMap<>();
            Map<ResourceLocation, Resource> resources = resourceManager.listResources("wall_styles", rl -> rl.getPath().endsWith(".json"));
            for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
                ResourceLocation fileId = entry.getKey();
                String path = fileId.getPath();
                String shortPath = path.substring("wall_styles/".length(), path.length() - ".json".length());
                ResourceLocation styleId = new ResourceLocation(fileId.getNamespace(), shortPath);
                try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                    JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                    loaded.put(styleId, parseStyle(styleId, obj));
                } catch (IOException | JsonParseException ex) {
                    throw new RuntimeException("Failed to parse wall style " + fileId, ex);
                }
            }
            return loaded;
        }

        @Override
        protected void apply(Map<ResourceLocation, WallStyle> prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
            STYLES.clear();
            STYLES.putAll(prepared);
        }
    }
}
