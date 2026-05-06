package com.example.villagewalls.world;

import com.example.villagewalls.VillageWalls;
import com.example.villagewalls.config.WallStyle;
import com.example.villagewalls.config.WallStyleRegistry;
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
    private static final int MAX_AUTOMATIC_BUILDS_PER_TICK = 1;
    private static final int CELL_SIZE_BITS = 7;
    private static final int PROCESSED_CELL_RADIUS = 2;

    private final VillageWallGenerator generator = new VillageWallGenerator();
    private final Map<ResourceKey<Level>, Map<CellKey, PendingBuild>> pending = new HashMap<>();

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ChunkPos chunk = event.getChunk().getPos();
        ProcessedVillages processed = ProcessedVillages.get(level);
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);

        level.structureManager()
                .startsForStructure(chunk, structure -> isVillageStructure(structures, structure))
                .stream()
                .filter(StructureStart::isValid)
                .forEach(start -> {
                    BlockPos origin = start.getBoundingBox().getCenter();
                    CellKey key = CellKey.from(origin);
                    if (!processed.contains(key)) {
                        pending.computeIfAbsent(level.dimension(), ignored -> new HashMap<>())
                                .putIfAbsent(key, new PendingBuild(origin, BUILD_DELAY_TICKS));
                    }
                });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();
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

            VillageWallGenerator.Result result = generator.generate(level, build.origin(), SEARCH_RADIUS, BUFFER_RADIUS, style.get(), MAX_DOORS);
            if (result.perimeterPoints() >= 4) {
                processed.addArea(entry.getKey(), PROCESSED_CELL_RADIUS);
            } else {
                processed.add(entry.getKey());
            }
            iterator.remove();
            return true;
        }
        return false;
    }

    private record PendingBuild(BlockPos origin, int ticksRemaining) {
        PendingBuild tickDown() {
            return new PendingBuild(origin, ticksRemaining - 1);
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
        private static final String NAME = VillageWalls.MOD_ID + "_processed_villages_v6";
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

        void addArea(CellKey center, int radius) {
            boolean changed = false;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    changed |= cells.add(new CellKey(center.x() + dx, center.z() + dz));
                }
            }
            if (changed) {
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
