package com.bettercontent.bettervillagefortifications.world;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import net.minecraft.server.MinecraftServer;

/** No circular WM dependency. WM validates full immutable binding before successor health. */
final class WorldAxiomPolicy {
    private WorldAxiomPolicy() {}
    static boolean fortified(MinecraftServer server) {
        Path root = server.getServerDirectory().toPath().toAbsolutePath().normalize();
        Path path = root.resolve("kubejs/config/world_axioms_runtime.json");
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IllegalStateException("Symlink in world policy projection path");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536)
                throw new IllegalArgumentException("Invalid world policy projection file");
            var value = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!value.keySet().equals(java.util.Set.of("schema", "lineage", "generation", "transaction", "selected", "configuration", "build_sha256"))
                    || !value.get("lineage").getAsString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || !value.get("configuration").isJsonObject()
                    || !value.get("generation").isJsonPrimitive() || !value.get("generation").getAsJsonPrimitive().isNumber()
                    || !value.get("generation").getAsString().matches("0|[1-9][0-9]*")
                    || !value.get("schema").getAsString().equals("world_axioms_runtime_v1"))
                throw new IllegalArgumentException("Unknown world policy projection schema");
            for (String field : java.util.List.of("schema", "lineage", "transaction", "build_sha256"))
                if (!value.get(field).isJsonPrimitive() || !value.get(field).getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Projection identity must be strings");
            var known = java.util.Set.of("iron_law", "eternal_engine", "alloy_testament", "captive_hearth",
                    "ark", "seed_covenant", "patient_hive", "familiars_name", "cartographers_memory", "font_atlas", "prospectors_eye", "celestial_almanac",
                    "communal_archive", "fortified_beginnings", "settled_arrival", "founders_exchange", "sacrificial_tradition", "spirit_awakening", "chalk_covenant", "patient_alchemist",
                    "shaped_horizon", "turning_year", "deep_compact", "chosen_omen");
            var selected = new HashSet<String>();
            for (var element : value.getAsJsonArray("selected"))
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                        || !known.contains(element.getAsString()) || !selected.add(element.getAsString()))
                    throw new IllegalArgumentException("Duplicate/invalid world policy projection selection");
            long generation = Long.parseLong(value.get("generation").getAsString());
            if (generation == 0 && selected.isEmpty() && value.get("transaction").getAsString().isEmpty()
                    && value.get("build_sha256").getAsString().isEmpty() && value.getAsJsonObject("configuration").size() == 0) return false;
            if (generation <= 0 || selected.size() > Math.min(24, generation)
                    || !value.get("lineage").getAsString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || !value.get("transaction").getAsString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || !value.get("build_sha256").getAsString().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid world policy projection identity");
            return selected.contains("fortified_beginnings");
        } catch (Exception broken) {
            throw new IllegalStateException("Malformed present world policy projection", broken);
        }
    }
}
