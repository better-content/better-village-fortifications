package com.bettercontent.bettervillagefortifications.world;

import com.bettercontent.bettervillagefortifications.VillageWalls;
import com.bettercontent.bettervillagefortifications.config.VillageWallsConfig;
import com.bettercontent.bettervillagefortifications.config.WallStyle;
import com.bettercontent.bettervillagefortifications.config.WallStyleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class AutoVillageWallBuilder {
    // Keep the complete footprint inside ordinary loaded view distance so walls exist on approach.
    private static final int SEARCH_RADIUS = 64;
    private static final int BUFFER_RADIUS = 8;
    private static final int MAX_DOORS = 4;
    private static final int BUILD_DELAY_TICKS = 1;
    private static final int INCOMPLETE_SEARCH_RETRY_TICKS = 20;
    private static final int MAX_AUTOMATIC_BUILDS_PER_TICK = 1;
    private static final int CELL_SIZE_BITS = 4;
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 20;
    private static final int PLAYER_SCAN_CHUNK_RADIUS = 12;
    private static final int MAX_PRELOAD_CHUNKS = 121;
    private static final int PLACEMENT_PRELOAD_MARGIN = 16;
    private static final int MAX_PRELOADED_STYLE_THICKNESS = 16;
    private static final int MAX_NEW_CHUNK_REQUESTS_PER_TICK = 4;
    private static final int MAX_OUTSTANDING_CHUNK_REQUESTS = 16;
    private static final int MAX_ACTIVE_PRELOADS = 2;
    private static final int PRELOAD_TIMEOUT_TICKS = 600;
    private static final int FULL_CHUNK_TICKET_LEVEL = 33;
    private static final TicketType<ChunkPos> PRELOAD_TICKET = TicketType.create(
            "better_village_fortifications_preload", (left, right) -> Long.compare(left.toLong(), right.toLong()));

    private final VillageWallGenerator generator = new VillageWallGenerator();
    private final Map<ResourceKey<Level>, Map<CellKey, PendingBuild>> pending = new HashMap<>();
    private int playerScanCooldown = PLAYER_SCAN_INTERVAL_TICKS;

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!VillageWallsConfig.AUTOMATIC_WALLS_ENABLED.get()) {
            return;
        }

        enqueueVillageStarts(level, event.getChunk().getPos(), "chunk_load");
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            clearLevel(level.dimension());
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        for (ResourceKey<Level> dimension : snapshotKeys(pending)) {
            clearLevel(dimension);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (VillageWallsConfig.AUTOMATIC_WALLS_ENABLED.get()) {
            playerScanCooldown--;
            if (playerScanCooldown <= 0) {
                playerScanCooldown = PLAYER_SCAN_INTERVAL_TICKS;
                scanAroundPlayers(server);
            }
        }
        if (pending.isEmpty()) {
            return;
        }

        int buildsThisTick = 0;
        int outstanding = pending.values().stream()
                .flatMap(builds -> builds.values().stream())
                .mapToInt(PendingBuild::inFlight)
                .sum();
        long activePreloads = pending.values().stream()
                .flatMap(builds -> builds.values().stream())
                .filter(build -> build.preloader != null)
                .count();
        PreloadBudget budget = new PreloadBudget(MAX_NEW_CHUNK_REQUESTS_PER_TICK,
                Math.max(0, MAX_OUTSTANDING_CHUNK_REQUESTS - outstanding),
                Math.max(0, MAX_ACTIVE_PRELOADS - (int) activePreloads));
        for (ResourceKey<Level> dimension : snapshotKeys(pending)) {
            Map<CellKey, PendingBuild> levelPending = pending.get(dimension);
            if (levelPending == null) {
                continue;
            }
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                clearLevel(dimension);
                continue;
            }

            boolean built = processLevel(level, levelPending, buildsThisTick < MAX_AUTOMATIC_BUILDS_PER_TICK, budget);
            if (built) {
                buildsThisTick++;
            }
            if (levelPending.isEmpty()) {
                pending.remove(dimension, levelPending);
            }
        }
    }

    private boolean processLevel(ServerLevel level, Map<CellKey, PendingBuild> levelPending, boolean mayBuild, PreloadBudget budget) {
        ProcessedVillages processed = ProcessedVillages.get(level);
        for (CellKey key : snapshotKeys(levelPending)) {
            PendingBuild build = levelPending.get(key);
            if (build == null) {
                continue;
            }
            if (build.ticksRemaining-- > 0) {
                continue;
            }

            if (processed.contains(key)) {
                build.close();
                levelPending.remove(key);
                continue;
            }
            if (!playerNearVillage(level, build.origin)) {
                build.resetPreload();
                continue;
            }

            if (!build.chanceRolled && level.getRandom().nextDouble() >= VillageWallsConfig.AUTOMATIC_WALL_CHANCE.get()) {
                VillageWalls.LOGGER.info("Skipping automatic wall for village cell {} by configured chance {}", key, VillageWallsConfig.AUTOMATIC_WALL_CHANCE.get());
                processed.add(key);
                levelPending.remove(key);
                continue;
            }
            build.chanceRolled = true;

            if (!build.preloadDisabled && !advancePreload(level, build, budget)) {
                if (build.noStyle) {
                    levelPending.remove(key);
                }
                continue;
            }
            if (build.preloadDisabled && ChunkLoadTracker.firstMissingSearchChunk(
                    build.origin, SEARCH_RADIUS, level.getChunkSource()::hasChunk).isPresent()) {
                build.ticksRemaining = INCOMPLETE_SEARCH_RETRY_TICKS;
                continue;
            }
            if (build.style == null && !resolveStyle(level, build)) {
                levelPending.remove(key);
                continue;
            }
            if (!mayBuild) {
                continue;
            }

            VillageWallGenerator.Result result;
            try {
                result = build.preparation == null
                        ? generator.generate(level, build.origin, SEARCH_RADIUS, BUFFER_RADIUS, build.style, MAX_DOORS)
                        : generator.generate(level, build.preparation, build.origin, SEARCH_RADIUS, build.style, MAX_DOORS);
            } catch (RuntimeException failure) {
                build.close();
                throw failure;
            }
            if (result.status() == VillageWallGenerator.Status.INCOMPLETE_SEARCH_AREA) {
                build.resetPreload();
                build.ticksRemaining = INCOMPLETE_SEARCH_RETRY_TICKS;
                continue;
            }
            if (result.perimeterPoints() >= 4) {
                VillageWalls.LOGGER.info("Built automatic wall for village cell {} at {} with {} perimeter points", key, build.origin, result.perimeterPoints());
                processed.add(key);
            } else {
                VillageWalls.LOGGER.warn("Automatic wall generation found no valid footprint for village cell {} at {}; it will be retried if the village is seen again", key, build.origin);
            }
            build.close();
            levelPending.remove(key);
            return true;
        }
        return false;
    }

    private boolean advancePreload(ServerLevel level, PendingBuild build, PreloadBudget budget) {
        if (build.preloader == null) {
            if (budget.activeSlots <= 0) {
                return false;
            }
            budget.activeSlots--;
            build.preloader = new VillageChunkPreloader(new LevelChunkLoader(level));
            build.preloader.addTargets(ChunkLoadTracker.searchChunks(build.origin, SEARCH_RADIUS), MAX_PRELOAD_CHUNKS);
        }
        if (++build.preloadTicks > PRELOAD_TIMEOUT_TICKS) {
            VillageWalls.LOGGER.warn("Village wall preload timed out at {}; using loaded chunks", build.origin);
            build.disablePreload();
            return false;
        }

        VillageChunkPreloader.TickResult progress = build.preloader.tick(budget.requests, budget.outstanding);
        budget.consume(progress.issued());
        if (progress.status() == VillageChunkPreloader.Status.FAILED) {
            VillageWalls.LOGGER.warn("Village wall preload failed at {}; using loaded chunks", build.origin);
            build.disablePreload();
            return false;
        }
        if (progress.status() != VillageChunkPreloader.Status.READY) {
            return false;
        }

        if (build.style == null && !resolveStyle(level, build)) {
            build.resetPreload();
            return false;
        }
        if (build.preparation == null) {
            build.preparation = generator.prepare(level, build.origin, SEARCH_RADIUS, BUFFER_RADIUS);
            if (build.preparation.status() == VillageWallGenerator.Status.INCOMPLETE_SEARCH_AREA) {
                build.resetPreload();
                build.ticksRemaining = INCOMPLETE_SEARCH_RETRY_TICKS;
                return false;
            }
            if (build.style.thickness() > MAX_PRELOADED_STYLE_THICKNESS) {
                VillageWalls.LOGGER.debug("Village wall at {} uses a style too wide for bounded preloading", build.origin);
                build.disablePreload();
                return false;
            }
            if (!build.preloader.addTargets(generator.placementChunks(build.preparation,
                    PLACEMENT_PRELOAD_MARGIN + build.style.thickness()), MAX_PRELOAD_CHUNKS)) {
                VillageWalls.LOGGER.debug("Village wall at {} exceeds the bounded preload area; using loaded chunks", build.origin);
                build.disablePreload();
                return false;
            }
        }
        progress = build.preloader.tick(budget.requests, budget.outstanding);
        budget.consume(progress.issued());
        if (progress.status() == VillageChunkPreloader.Status.FAILED) {
            build.disablePreload();
            return false;
        }
        return progress.status() == VillageChunkPreloader.Status.READY;
    }

    private static boolean resolveStyle(ServerLevel level, PendingBuild build) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation biomeId = biomes.getKey(level.getBiome(build.origin).value());
        Optional<WallStyle> style = biomeId == null
                ? WallStyleRegistry.getStyle(WallStyleRegistry.defaultStyleId())
                : WallStyleRegistry.selectForBiome(biomeId);
        if (style.isEmpty()) {
            VillageWalls.LOGGER.warn("No wall style is available for village at {}", build.origin);
            build.noStyle = true;
            return false;
        }
        build.style = style.get();
        return true;
    }

    private static boolean playerNearVillage(ServerLevel level, BlockPos origin) {
        ChunkPos village = new ChunkPos(origin);
        return level.players().stream().anyMatch(player -> {
            ChunkPos nearby = player.chunkPosition();
            return Math.abs(nearby.x - village.x) <= PLAYER_SCAN_CHUNK_RADIUS
                    && Math.abs(nearby.z - village.z) <= PLAYER_SCAN_CHUNK_RADIUS;
        });
    }

    private void clearLevel(ResourceKey<Level> dimension) {
        Map<CellKey, PendingBuild> removed = pending.remove(dimension);
        if (removed != null) {
            removed.values().forEach(PendingBuild::close);
        }
    }

    static <K> List<K> snapshotKeys(Map<K, ?> source) {
        return new ArrayList<>(source.keySet());
    }

    private void scanAroundPlayers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            level.players().forEach(player -> {
                ChunkPos playerChunk = player.chunkPosition();
                ChunkPos.rangeClosed(
                                new ChunkPos(playerChunk.x - PLAYER_SCAN_CHUNK_RADIUS, playerChunk.z - PLAYER_SCAN_CHUNK_RADIUS),
                                new ChunkPos(playerChunk.x + PLAYER_SCAN_CHUNK_RADIUS, playerChunk.z + PLAYER_SCAN_CHUNK_RADIUS))
                        .filter(chunk -> level.getChunkSource().hasChunk(chunk.x, chunk.z))
                        .forEach(chunk -> enqueueVillageStarts(level, chunk, "player_scan"));
            });
        }
    }

    private void enqueueVillageStarts(ServerLevel level, ChunkPos chunk, String source) {
        ProcessedVillages processed = ProcessedVillages.get(level);
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<CellKey, PendingBuild> levelPending = pending.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());

        level.structureManager()
                .startsForStructure(chunk, structure -> isVillageStructure(structures, structure))
                .stream()
                .filter(StructureStart::isValid)
                .forEach(start -> {
                    BlockPos origin = start.getBoundingBox().getCenter();
                    if (!playerNearVillage(level, origin)) {
                        return;
                    }
                    CellKey key = CellKey.from(origin);
                    if (!processed.contains(key) && !levelPending.containsKey(key)) {
                        VillageWalls.LOGGER.info("Queued automatic wall for village cell {} at {} from {}", key, origin, source);
                        levelPending.put(key, new PendingBuild(origin));
                    }
                });

        if (levelPending.isEmpty()) {
            pending.remove(level.dimension());
        }
    }

    private static class PendingBuild {
        final BlockPos origin;
        int ticksRemaining = BUILD_DELAY_TICKS;
        int preloadTicks;
        boolean preloadDisabled;
        boolean chanceRolled;
        boolean noStyle;
        WallStyle style;
        VillageWallGenerator.Preparation preparation;
        VillageChunkPreloader preloader;

        PendingBuild(BlockPos origin) {
            this.origin = origin;
        }

        int inFlight() {
            return preloader == null ? 0 : preloader.inFlight();
        }

        void resetPreload() {
            close();
            preparation = null;
            preloadTicks = 0;
        }

        void disablePreload() {
            resetPreload();
            preloadDisabled = true;
            ticksRemaining = INCOMPLETE_SEARCH_RETRY_TICKS;
        }

        void close() {
            if (preloader != null) {
                preloader.close();
                preloader = null;
            }
        }
    }

    private static class PreloadBudget {
        int requests;
        int outstanding;
        int activeSlots;

        PreloadBudget(int requests, int outstanding, int activeSlots) {
            this.requests = requests;
            this.outstanding = outstanding;
            this.activeSlots = activeSlots;
        }

        void consume(int issued) {
            requests -= issued;
            outstanding -= issued;
        }
    }

    private static class LevelChunkLoader implements VillageChunkPreloader.Loader {
        private final ServerLevel level;

        LevelChunkLoader(ServerLevel level) {
            this.level = level;
        }

        @Override
        public boolean hasChunk(ChunkPos chunk) {
            return level.getChunkSource().hasChunk(chunk.x, chunk.z);
        }

        @Override
        public CompletableFuture<Boolean> request(ChunkPos chunk) {
            level.getChunkSource().addRegionTicket(PRELOAD_TICKET, chunk, FULL_CHUNK_TICKET_LEVEL, chunk);
            try {
                return level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true)
                        .thenApply(result -> result.left().isPresent());
            } catch (RuntimeException failure) {
                release(chunk);
                throw failure;
            }
        }

        @Override
        public void release(ChunkPos chunk) {
            level.getChunkSource().removeRegionTicket(PRELOAD_TICKET, chunk, FULL_CHUNK_TICKET_LEVEL, chunk);
        }
    }

    private static boolean isVillageStructure(Registry<Structure> structures, Structure structure) {
        ResourceLocation id = structures.getKey(structure);
        if (id == null) {
            return false;
        }
        return structures.getHolder(ResourceKey.create(Registries.STRUCTURE, id))
                .map(holder -> holder.is(StructureTags.VILLAGE))
                .orElse(false);
    }

    private record CellKey(int x, int z) {
        static CellKey from(BlockPos pos) {
            return new CellKey(pos.getX() >> CELL_SIZE_BITS, pos.getZ() >> CELL_SIZE_BITS);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", x);
            tag.putInt("z", z);
            return tag;
        }

        static CellKey load(CompoundTag tag) {
            return new CellKey(tag.getInt("x"), tag.getInt("z"));
        }
    }

    private static class ProcessedVillages extends SavedData {
        private static final String NAME = VillageWalls.MOD_ID + "_processed_villages_v7";
        private final Set<CellKey> cells = new HashSet<>();

        static ProcessedVillages get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(ProcessedVillages::load, ProcessedVillages::new, NAME);
        }

        private static ProcessedVillages load(CompoundTag tag) {
            ProcessedVillages data = new ProcessedVillages();
            ListTag cells = tag.getList("cells", Tag.TAG_COMPOUND);
            for (int i = 0; i < cells.size(); i++) {
                data.cells.add(CellKey.load(cells.getCompound(i)));
            }
            return data;
        }

        boolean contains(CellKey key) {
            return cells.contains(key);
        }

        void add(CellKey key) {
            if (cells.add(key)) {
                setDirty();
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag savedCells = new ListTag();
            for (CellKey cell : cells) {
                savedCells.add(cell.save());
            }
            tag.put("cells", savedCells);
            return tag;
        }
    }
}
