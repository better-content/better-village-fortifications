package com.bettercontent.villagewalls.world;

import com.bettercontent.villagewalls.VillageWalls;
import com.bettercontent.villagewalls.config.VillageWallsConfig;
import com.bettercontent.villagewalls.config.WallStyle;
import com.bettercontent.villagewalls.config.WallStyleRegistry;
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
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AutoVillageWallBuilder {
    private static final int SEARCH_RADIUS = 96;
    private static final int BUFFER_RADIUS = 8;
    private static final int MAX_DOORS = 4;
    private static final int BUILD_DELAY_TICKS = 20;
    private static final int INCOMPLETE_SEARCH_RETRY_TICKS = 100;
    private static final int MAX_AUTOMATIC_BUILDS_PER_TICK = 1;
    private static final int CELL_SIZE_BITS = 4;
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 100;
    private static final int PLAYER_SCAN_CHUNK_RADIUS = 8;

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
        Iterator<Map.Entry<ResourceKey<Level>, Map<CellKey, PendingBuild>>> levelIterator = pending.entrySet().iterator();
        while (levelIterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Map<CellKey, PendingBuild>> levelEntry = levelIterator.next();
            ServerLevel level = server.getLevel(levelEntry.getKey());
            if (level == null) {
                levelIterator.remove();
                continue;
            }

            boolean built = processLevel(level, levelEntry.getValue(), buildsThisTick < MAX_AUTOMATIC_BUILDS_PER_TICK);
            if (built) {
                buildsThisTick++;
            }
            if (levelEntry.getValue().isEmpty()) {
                levelIterator.remove();
            }
            if (buildsThisTick >= MAX_AUTOMATIC_BUILDS_PER_TICK) {
                break;
            }
        }
    }

    private boolean processLevel(ServerLevel level, Map<CellKey, PendingBuild> levelPending, boolean mayBuild) {
        Optional<WallStyle> style = WallStyleRegistry.getStyle(WallStyleRegistry.defaultStyleId());
        if (style.isEmpty()) {
            return false;
        }

        ProcessedVillages processed = ProcessedVillages.get(level);
        Iterator<Map.Entry<CellKey, PendingBuild>> iterator = levelPending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CellKey, PendingBuild> entry = iterator.next();
            PendingBuild build = entry.getValue().tickDown();
            if (build.ticksRemaining() > 0) {
                entry.setValue(build);
                continue;
            }

            if (processed.contains(entry.getKey())) {
                iterator.remove();
                continue;
            }
            if (!mayBuild) {
                entry.setValue(build);
                continue;
            }

            Optional<ChunkPos> missingChunk = ChunkLoadTracker.firstMissingSearchChunk(
                    build.origin(),
                    SEARCH_RADIUS,
                    level.getChunkSource()::hasChunk
            );
            if (missingChunk.isPresent()) {
                VillageWalls.LOGGER.debug(
                        "Deferring automatic wall for village cell {} at {}; search chunk {} is not loaded yet",
                        entry.getKey(),
                        build.origin(),
                        missingChunk.get()
                );
                entry.setValue(build.retryAfter(INCOMPLETE_SEARCH_RETRY_TICKS));
                continue;
            }

            if (level.getRandom().nextDouble() >= VillageWallsConfig.AUTOMATIC_WALL_CHANCE.get()) {
                VillageWalls.LOGGER.info("Skipping automatic wall for village cell {} by configured chance {}", entry.getKey(), VillageWallsConfig.AUTOMATIC_WALL_CHANCE.get());
                processed.add(entry.getKey());
                iterator.remove();
                return true;
            }

            VillageWallGenerator.Result result = generator.generate(level, build.origin(), SEARCH_RADIUS, BUFFER_RADIUS, style.get(), MAX_DOORS);
            if (result.status() == VillageWallGenerator.Status.INCOMPLETE_SEARCH_AREA) {
                VillageWalls.LOGGER.debug("Deferring automatic wall for village cell {} at {}; search area became incomplete during generation", entry.getKey(), build.origin());
                entry.setValue(build.retryAfter(INCOMPLETE_SEARCH_RETRY_TICKS));
                continue;
            }
            if (result.perimeterPoints() >= 4) {
                VillageWalls.LOGGER.info("Built automatic wall for village cell {} at {} with {} perimeter points", entry.getKey(), build.origin(), result.perimeterPoints());
                processed.add(entry.getKey());
            } else {
                VillageWalls.LOGGER.warn("Automatic wall generation found no valid footprint for village cell {} at {}; it will be retried if the village is seen again", entry.getKey(), build.origin());
            }
            iterator.remove();
            return true;
        }
        return false;
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
                    CellKey key = CellKey.from(origin);
                    if (!processed.contains(key) && !levelPending.containsKey(key)) {
                        VillageWalls.LOGGER.info("Queued automatic wall for village cell {} at {} from {}", key, origin, source);
                        levelPending.put(key, new PendingBuild(origin, BUILD_DELAY_TICKS));
                    }
                });

        if (levelPending.isEmpty()) {
            pending.remove(level.dimension());
        }
    }

    private record PendingBuild(BlockPos origin, int ticksRemaining) {
        PendingBuild tickDown() {
            return new PendingBuild(origin, ticksRemaining - 1);
        }

        PendingBuild retryAfter(int ticks) {
            return new PendingBuild(origin, ticks);
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
