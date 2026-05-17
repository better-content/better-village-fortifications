package com.example.villagewalls.world;

import com.example.villagewalls.config.WallStyle;
import com.example.villagewalls.logic.DoorPlanner;
import com.example.villagewalls.logic.GridPos;
import com.example.villagewalls.logic.SegmentFlatness;
import com.example.villagewalls.logic.VillageOutlineSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class VillageWallGenerator {
    private static final int MIN_ENCLOSURE_BUFFER = 6;
    private static final int MIN_VISIBLE_WALL_HEIGHT = 3;
    private static final int MAX_VISIBLE_WALL_HEIGHT = 6;
    private static final int VINE_SPACING = 9;
    private static final int PATH_GATE_SCAN_DEPTH = 12;
    private static final int PATH_GATE_SCORE_BONUS = 100_000;
    private static final int MIN_CAMPFIRE_OFFSET = 2;
    private static final int MAX_CAMPFIRE_OFFSET = 4;
    private static final int CAMPFIRE_LIGHT_LEVEL = 15;
    private static final int TARGET_CAMPFIRE_LIGHT = 8;
    private static final int CAMPFIRE_TARGET_DEPTH = 10;
    private static final int CAMPFIRE_MIN_SEPARATION = 11;
    private static final int CAMPFIRE_MIN_GAIN = 5;
    private static final int GATE_APRON_DEPTH = 3;
    private static final int RAMPART_FOOTPRINT_THRESHOLD = 5_000;
    private static final int RAMPART_PERIMETER_THRESHOLD = 300;
    private static final int RAMPART_LADDER_SEGMENT_SPACING = 12;
    private static final int RAMPART_TORCH_SPACING = 8;
    private static final boolean FORCE_RAMPARTS_FOR_TESTING = false;

    public record Result(int footprintPoints, int perimeterPoints, int doorsPlaced) {
    }

    public Result generate(ServerLevel level, BlockPos origin, int searchRadius, int bufferRadius, WallStyle style, int maxDoors) {
        VillageFootprint footprint = collectFootprint(level, origin, searchRadius);
        if (footprint.points().isEmpty()) {
            return new Result(0, 0, 0);
        }

        List<GridPos> perimeter = VillageOutlineSolver.traceCleanRing(footprint.points(), Math.max(bufferRadius, MIN_ENCLOSURE_BUFFER));
        if (perimeter.size() < 4) {
            return new Result(footprint.points().size(), perimeter.size(), 0);
        }

        boolean inhabited = hasVillagers(level, origin, searchRadius);
        WallProfile profile = chooseWallProfile(footprint.points(), perimeter, style);
        announceFootprintDebug(level, footprint.points().size(), perimeter.size(), profile);
        TerrainSampler sampler = snapshotTerrain(level, perimeter, profile.style().thickness());
        int enclosureTopY = enclosureTopY(perimeter, sampler, profile.style().height());
        List<SegmentFlatness> segmentFlatness = scoreSegments(perimeter, sampler, level);
        List<Integer> doorSegments = DoorPlanner.chooseDoorSegments(segmentFlatness, maxDoors);

        int doorCount = 0;
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            boolean placeDoor = doorSegments.contains(i);
            if (placeDoor) {
                doorCount++;
            }
            placeSegment(level, a, b, sampler, profile, placeDoor, enclosureTopY, footprint.points(), i, inhabited);
        }
        if (inhabited) {
            placeLightingCampfires(level, perimeter, footprint.points(), profile);
        }
        return new Result(footprint.points().size(), perimeter.size(), doorCount * 2);
    }

    private static boolean hasVillagers(ServerLevel level, BlockPos origin, int searchRadius) {
        return !level.getEntitiesOfClass(Villager.class, new AABB(origin).inflate(searchRadius)).isEmpty();
    }

    private static WallProfile chooseWallProfile(Set<GridPos> footprint, List<GridPos> perimeter, WallStyle requestedStyle) {
        boolean rampart = FORCE_RAMPARTS_FOR_TESTING
                || (footprint.size() >= RAMPART_FOOTPRINT_THRESHOLD && perimeter.size() >= RAMPART_PERIMETER_THRESHOLD);
        if (!rampart) {
            return new WallProfile(requestedStyle, false);
        }
        WallStyle rampartStyle = new WallStyle(
                new ResourceLocation("villagewalls", "rampart_stonebrick"),
                "minecraft:stone_bricks",
                "minecraft:mossy_stone_bricks",
                5,
                3,
                MAX_VISIBLE_WALL_HEIGHT,
                true
        );
        return new WallProfile(rampartStyle, true);
    }

    private record WallProfile(WallStyle style, boolean rampart) {
    }

    private static void announceFootprintDebug(ServerLevel level, int footprintPoints, int perimeterPoints, WallProfile profile) {
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                "Village wall footprint: " + footprintPoints
                        + " cells, perimeter " + perimeterPoints
                        + ", style " + (profile.rampart() ? "rampart" : "small")
                        + (FORCE_RAMPARTS_FOR_TESTING ? " (forced)" : "")
        ), false);
    }

    public List<SegmentFlatness> scoreSegments(List<GridPos> perimeter, TerrainSampler sampler) {
        return scoreSegments(perimeter, sampler, null);
    }

    private List<SegmentFlatness> scoreSegments(List<GridPos> perimeter, TerrainSampler sampler, ServerLevel level) {
        List<SegmentFlatness> out = new ArrayList<>();
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            int score = segmentFlatnessScore(a, b, sampler);
            if (level != null) {
                Optional<PathHit> pathHit = closestPathHit(level, sampler, rasterLine(a, b), directionFromDelta(b.x() - a.x(), b.z() - a.z()));
                if (pathHit.isPresent()) {
                    score -= PATH_GATE_SCORE_BONUS - (pathHit.get().distance() * 1_000);
                }
            }
            out.add(new SegmentFlatness(i, score));
        }
        return out;
    }

    private TerrainSampler snapshotTerrain(ServerLevel level, List<GridPos> perimeter, int thickness) {
        Map<GridPos, Integer> heights = new HashMap<>();
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            for (GridPos p : rasterLine(a, b)) {
                for (int t = 0; t < thickness; t++) {
                    GridPos offset = offsetForThickness(p, a, b, t);
                    heights.putIfAbsent(offset, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, offset.x(), offset.z()));
                }
            }
        }
        return (x, z) -> heights.computeIfAbsent(new GridPos(x, z),
                key -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, key.x(), key.z()));
    }

    public int segmentFlatnessScore(GridPos a, GridPos b, TerrainSampler sampler) {
        List<GridPos> line = rasterLine(a, b);
        if (line.size() < 4) {
            return Integer.MAX_VALUE;
        }

        int dx = Integer.compare(b.x(), a.x());
        int dz = Integer.compare(b.z(), a.z());
        int normalX = -dz;
        int normalZ = dx;
        int middle = line.size() / 2;
        int start = Math.max(0, middle - 3);
        int end = Math.min(line.size() - 1, middle + 3);

        List<Integer> heights = new ArrayList<>();
        int previousCenterY = Integer.MIN_VALUE;
        int slopeTotal = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = start; i <= end; i++) {
            GridPos p = line.get(i);
            int centerY = sampler.surfaceY(p.x(), p.z());
            if (previousCenterY != Integer.MIN_VALUE) {
                slopeTotal += Math.abs(centerY - previousCenterY);
            }
            previousCenterY = centerY;

            for (int offset = -2; offset <= 2; offset++) {
                int y = sampler.surfaceY(p.x() + (normalX * offset), p.z() + (normalZ * offset));
                heights.add(y);
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        int range = max - min;
        int average = heights.stream().mapToInt(Integer::intValue).sum() / heights.size();
        int roughness = 0;
        for (int y : heights) {
            roughness += Math.abs(y - average);
        }
        return (range * 10) + (slopeTotal * 4) + roughness;
    }

    private VillageFootprint collectFootprint(ServerLevel level, BlockPos origin, int searchRadius) {
        Set<GridPos> points = new HashSet<>();

        points.addAll(collectVillageStructureFootprint(level, origin, searchRadius));
        points.addAll(collectVillagePoiFootprint(level, origin, searchRadius));

        return new VillageFootprint(points);
    }

    private Set<GridPos> collectVillagePoiFootprint(ServerLevel level, BlockPos origin, int searchRadius) {
        Set<GridPos> points = new HashSet<>();
        level.getPoiManager().ensureLoadedAndValid(level, origin, searchRadius);
        Predicate<Holder<PoiType>> villagePoi = holder -> holder.is(PoiTypeTags.VILLAGE);
        level.getPoiManager()
                .findAll(villagePoi, pos -> true, origin, searchRadius, PoiManager.Occupancy.ANY)
                .forEach(pos -> points.add(new GridPos(pos.getX(), pos.getZ())));
        return points;
    }

    private Set<GridPos> collectVillageStructureFootprint(ServerLevel level, BlockPos origin, int searchRadius) {
        Set<GridPos> points = new HashSet<>();
        ChunkPos center = new ChunkPos(origin);
        int chunkRadius = Math.max(1, (searchRadius + 15) >> 4);
        StructureManager structureManager = level.structureManager();
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<String> seenStarts = new HashSet<>();
        ChunkPos.rangeClosed(new ChunkPos(center.x - chunkRadius, center.z - chunkRadius),
                        new ChunkPos(center.x + chunkRadius, center.z + chunkRadius))
                .filter(chunk -> level.getChunkSource().hasChunk(chunk.x, chunk.z))
                .flatMap(chunk -> structureManager.startsForStructure(chunk, structure -> isVillageStructure(structures, structure)).stream())
                .filter(StructureStart::isValid)
                .filter(start -> seenStarts.add(startKey(structures, start)))
                .flatMap(start -> start.getPieces().stream())
                .map(StructurePiece::getBoundingBox)
                .filter(box -> isNear(box, origin, searchRadius))
                .forEach(box -> addBoundingBoxFootprint(points, box));
        return points;
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

    private static String startKey(Registry<Structure> structures, StructureStart start) {
        ResourceLocation id = structures.getKey(start.getStructure());
        return start.getChunkPos().toLong() + "|" + (id == null ? "unknown" : id.toString());
    }

    private static boolean isNear(BoundingBox box, BlockPos origin, int searchRadius) {
        return box.intersects(origin.getX() - searchRadius, origin.getZ() - searchRadius,
                origin.getX() + searchRadius, origin.getZ() + searchRadius);
    }

    private static void addBoundingBoxFootprint(Set<GridPos> points, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                points.add(new GridPos(x, z));
            }
        }
    }

    private record VillageFootprint(Set<GridPos> points) {
    }

    private void placeSegment(ServerLevel level, GridPos a, GridPos b, TerrainSampler sampler, WallProfile profile, boolean placeDoor, int enclosureTopY, Set<GridPos> footprint, int segmentIndex, boolean inhabited) {
        List<GridPos> line = rasterLine(a, b);
        if (line.isEmpty()) {
            return;
        }
        int mid = line.size() / 2;
        Direction along = directionFromDelta(b.x() - a.x(), b.z() - a.z());
        Direction normal = along.getClockWise();
        Direction inside = insideDirection(line.get(mid), normal, footprint);
        WallStyle style = profile.style();
        List<List<BlockPos>> rampartRoofLanes = rampartRoofLanes(profile);
        List<BlockPos> rampartInteriorFloor = new ArrayList<>();
        int doorIndex = placeDoor ? preferredDoorIndex(level, sampler, line, along).orElse(mid) : -1;
        if (doorIndex >= 0 && (hasSurfaceWater(level, line.get(doorIndex)) || hasSurfaceWater(level, line.get(Math.min(line.size() - 1, doorIndex + 1))))) {
            doorIndex = -1;
        }
        for (int i = 0; i < line.size(); i++) {
            GridPos p = line.get(i);
            int terrainY = sampler.surfaceY(p.x(), p.z());
            boolean doorBandStart = placeDoor && doorIndex >= 0 && i == doorIndex;
            boolean doorBandSkip = placeDoor && doorIndex >= 0 && i == doorIndex + 1;
            if (doorBandStart) {
                placeDoubleDoor(level, p, a, b, inside, sampler, Blocks.STONE_BRICKS.defaultBlockState(), style.thickness());
                continue;
            }
            if (doorBandSkip) {
                continue;
            }
            for (int t = 0; t < style.thickness(); t++) {
                GridPos offset = profile.rampart() ? rampartOffset(p, inside, t, style.thickness()) : offsetForThickness(p, a, b, t);
                int columnBaseY = wallColumnBaseY(level, sampler, offset);
                int visibleBaseY = sampler.surfaceY(offset.x(), offset.z());
                int visualTopY = cappedWallTopY(visibleBaseY, enclosureTopY, MIN_VISIBLE_WALL_HEIGHT);
                int roofY = profile.rampart() ? Math.max(columnBaseY, visualTopY - 1) : visualTopY;
                int bodyTopY = style.walkable() ? Math.max(columnBaseY, roofY - 1) : visualTopY;
                boolean hollowRampartLane = profile.rampart() && isRampartInteriorLane(t, style.thickness());
                if (hollowRampartLane) {
                    int interiorFloorY = Math.max(columnBaseY, roofY - 2);
                    for (int y = columnBaseY; y <= interiorFloorY; y++) {
                        level.setBlock(new BlockPos(offset.x(), y, offset.z()), stoneBrickState(p, y), Block.UPDATE_ALL);
                    }
                    BlockPos interiorFloor = new BlockPos(offset.x(), interiorFloorY, offset.z());
                    level.setBlock(interiorFloor, stoneBrickState(p, columnBaseY), Block.UPDATE_ALL);
                    rampartInteriorFloor.add(interiorFloor);
                    for (int y = interiorFloorY + 1; y < roofY; y++) {
                        level.setBlock(new BlockPos(offset.x(), y, offset.z()), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                } else {
                    for (int y = columnBaseY; y <= bodyTopY; y++) {
                        BlockPos pos = new BlockPos(offset.x(), y, offset.z());
                        level.setBlock(pos, stoneBrickState(p, y), Block.UPDATE_ALL);
                    }
                }
                if (style.walkable()) {
                    BlockPos roof = new BlockPos(offset.x(), roofY, offset.z());
                    level.setBlock(roof, stoneBrickState(offset, roofY), Block.UPDATE_ALL);
                    if (profile.rampart()) {
                        rampartRoofLanes.get(t).add(roof);
                    }
                    if (profile.rampart() && (t == 0 || t == style.thickness() - 1)) {
                        BlockPos parapet = new BlockPos(offset.x(), visualTopY, offset.z());
                        level.setBlock(parapet, rampartParapetState(p, i), Block.UPDATE_ALL);
                    }
                }
                clearOldWallAboveCap(level, offset, visualTopY);
                roughenGroundBorder(level, offset, columnBaseY);
            }
            if (shouldPlaceVines(p, i, line.size(), placeDoor)) {
                placeVines(level, p, normal, terrainY + 1, cappedWallTopY(terrainY, enclosureTopY, MIN_VISIBLE_WALL_HEIGHT));
            }
        }
        if (profile.rampart()) {
            smoothRampartRoof(level, rampartRoofLanes);
            if (inhabited) {
                placeRampartTorches(level, rampartInteriorFloor);
            }
            if (!placeDoor && Math.floorMod(segmentIndex, RAMPART_LADDER_SEGMENT_SPACING) == 0) {
                placeRampartLadder(level, line.get(mid), inside, sampler, enclosureTopY, style.thickness());
            }
        }
    }

    private static List<List<BlockPos>> rampartRoofLanes(WallProfile profile) {
        List<List<BlockPos>> lanes = new ArrayList<>();
        if (profile.rampart()) {
            for (int i = 0; i < profile.style().thickness(); i++) {
                lanes.add(new ArrayList<>());
            }
        }
        return lanes;
    }

    private static void smoothRampartRoof(ServerLevel level, List<List<BlockPos>> roofLanes) {
        for (List<BlockPos> lane : roofLanes) {
            for (int i = 0; i < lane.size() - 1; i++) {
                BlockPos current = lane.get(i);
                BlockPos next = lane.get(i + 1);
                int dy = next.getY() - current.getY();
                if (Math.abs(dy) != 1) {
                    continue;
                }
                Direction along = horizontalDirection(current, next);
                if (along == null) {
                    continue;
                }
                if (dy > 0) {
                    placeRampartStair(level, current, along);
                } else {
                    placeRampartStair(level, next, along.getOpposite());
                }
            }
        }
    }

    private static void placeRampartTorches(ServerLevel level, List<BlockPos> interiorFloor) {
        for (int i = RAMPART_TORCH_SPACING / 2; i < interiorFloor.size(); i += RAMPART_TORCH_SPACING) {
            BlockPos support = interiorFloor.get(i);
            BlockPos torchPos = support.above();
            if (level.getBlockState(torchPos).canBeReplaced()
                    && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
                level.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        if (Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ())) {
            if (dx > 0) {
                return Direction.EAST;
            }
            if (dx < 0) {
                return Direction.WEST;
            }
        }
        if (dz > 0) {
            return Direction.SOUTH;
        }
        if (dz < 0) {
            return Direction.NORTH;
        }
        return null;
    }

    private static void placeRampartStair(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
        level.setBlock(pos, stair, Block.UPDATE_ALL);
    }

    private static void placeRampartLadder(ServerLevel level, GridPos wallMidpoint, Direction inside, TerrainSampler sampler, int enclosureTopY, int rampartWidth) {
        int innerWallOffset = Math.max(1, rampartWidth / 2);
        GridPos ladderGrid = offsetToward(wallMidpoint, inside, innerWallOffset);
        if (hasSurfaceWater(level, ladderGrid)) {
            return;
        }
        int wallBaseY = sampler.surfaceY(wallMidpoint.x(), wallMidpoint.z());
        int ladderBaseY = sampler.surfaceY(ladderGrid.x(), ladderGrid.z());
        int roofY = Math.max(wallBaseY + 1, cappedWallTopY(wallBaseY, enclosureTopY, MIN_VISIBLE_WALL_HEIGHT) - 1);
        BlockPos ladderBase = new BlockPos(ladderGrid.x(), ladderBaseY, ladderGrid.z());
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, inside);
        for (int y = ladderBase.getY(); y <= roofY; y++) {
            BlockPos pos = new BlockPos(ladderBase.getX(), y, ladderBase.getZ());
            BlockPos support = pos.relative(inside.getOpposite());
            if (level.getBlockState(pos).canBeReplaced()
                    && level.getBlockState(support).isFaceSturdy(level, support, inside)) {
                level.setBlock(pos, ladder, Block.UPDATE_ALL);
            }
        }
    }

    private static boolean hasSurfaceWater(ServerLevel level, GridPos pos) {
        return waterDepthAtSurface(level, pos) > 0;
    }

    private static int wallColumnBaseY(ServerLevel level, TerrainSampler sampler, GridPos pos) {
        int baseY = sampler.surfaceY(pos.x(), pos.z());
        if (waterDepthAtSurface(level, pos) == 0) {
            return baseY;
        }
        int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.x(), pos.z());
        return Math.min(baseY, oceanFloorY);
    }

    private static int waterDepthAtSurface(ServerLevel level, GridPos pos) {
        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x(), pos.z());
        int y = worldSurfaceY - 1;
        int depth = 0;
        while (depth < 16 && y >= level.getMinBuildHeight()) {
            if (!isWaterOrFluid(level.getBlockState(new BlockPos(pos.x(), y, pos.z())))) {
                break;
            }
            depth++;
            y--;
        }
        return depth;
    }

    private static boolean isWaterOrFluid(BlockState state) {
        return state.is(Blocks.WATER) || !state.getFluidState().isEmpty();
    }

    private static int cappedWallTopY(int baseY, int enclosureTopY, int minVisibleHeight) {
        int desiredTopY = Math.max(baseY + minVisibleHeight, enclosureTopY);
        return Math.min(desiredTopY, baseY + MAX_VISIBLE_WALL_HEIGHT - 1);
    }

    private static void clearOldWallAboveCap(ServerLevel level, GridPos wallPos, int visualTopY) {
        for (int y = visualTopY + 1; y <= visualTopY + 32; y++) {
            BlockPos pos = new BlockPos(wallPos.x(), y, wallPos.z());
            if (isGeneratedWallBlock(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static boolean isGeneratedWallBlock(BlockState state) {
        return state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.STONE_BRICK_WALL)
                || state.is(Blocks.MOSSY_STONE_BRICK_WALL)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.LADDER)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.SMOOTH_STONE_SLAB)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.SPRUCE_LOG);
    }

    private static void roughenGroundBorder(ServerLevel level, GridPos wallPos, int wallBaseY) {
        BlockPos wallBase = new BlockPos(wallPos.x(), wallBaseY, wallPos.z());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = wallBase.relative(direction).below();
            BlockState state = level.getBlockState(side);
            if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) {
                level.setBlock(side, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static Direction insideDirection(GridPos wallPos, Direction normal, Set<GridPos> footprint) {
        GridPos normalPoint = new GridPos(wallPos.x() + normal.getStepX(), wallPos.z() + normal.getStepZ());
        GridPos oppositePoint = new GridPos(wallPos.x() - normal.getStepX(), wallPos.z() - normal.getStepZ());
        return minDist2(normalPoint, footprint) <= minDist2(oppositePoint, footprint) ? normal : normal.getOpposite();
    }

    private static int minDist2(GridPos point, Set<GridPos> targets) {
        int min = Integer.MAX_VALUE;
        for (GridPos target : targets) {
            min = Math.min(min, dist2(point, target));
        }
        return min;
    }

    private static int dist2(GridPos a, GridPos b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return (dx * dx) + (dz * dz);
    }

    private void placeLightingCampfires(ServerLevel level, List<GridPos> perimeter, Set<GridPos> footprint, WallProfile profile) {
        List<CampfireCandidate> candidates = campfireCandidates(level, perimeter, footprint, profile);
        Set<GridPos> unlitTargets = campfireLightTargets(level, perimeter, footprint, profile);
        List<GridPos> placed = new ArrayList<>();

        while (!unlitTargets.isEmpty()) {
            CampfireCandidate best = null;
            int bestGain = 0;
            for (CampfireCandidate candidate : candidates) {
                if (placed.stream().anyMatch(pos -> dist2(pos, candidate.gridPos()) < CAMPFIRE_MIN_SEPARATION * CAMPFIRE_MIN_SEPARATION)) {
                    continue;
                }
                int gain = lightGain(candidate, unlitTargets);
                if (gain > bestGain) {
                    best = candidate;
                    bestGain = gain;
                }
            }
            if (best == null || bestGain < CAMPFIRE_MIN_GAIN) {
                return;
            }

            CampfireCandidate selected = best;
            placeCampfire(level, selected);
            placed.add(selected.gridPos());
            unlitTargets.removeIf(target -> approximateCampfireLight(selected.gridPos(), target) >= TARGET_CAMPFIRE_LIGHT);
        }
    }

    private List<CampfireCandidate> campfireCandidates(ServerLevel level, List<GridPos> perimeter, Set<GridPos> footprint, WallProfile profile) {
        Map<GridPos, CampfireCandidate> candidates = new HashMap<>();
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            Direction along = directionFromDelta(b.x() - a.x(), b.z() - a.z());
            Direction normal = along.getClockWise();
            List<GridPos> line = rasterLine(a, b);
            if (line.size() < 3) {
                continue;
            }
            GridPos wallPos = line.get(line.size() / 2);
            Direction inside = insideDirection(wallPos, normal, footprint);
            campfireCandidate(level, wallPos, inside, profile).ifPresent(candidate -> candidates.putIfAbsent(candidate.gridPos(), candidate));
        }
        return new ArrayList<>(candidates.values());
    }

    private Optional<CampfireCandidate> campfireCandidate(ServerLevel level, GridPos wallPos, Direction inside, WallProfile profile) {
        if (hasSurfaceWater(level, wallPos)) {
            return Optional.empty();
        }
        int rampartInnerClear = profile.style().thickness() / 2 + 1;
        int minOffset = profile.rampart() ? rampartInnerClear + MIN_CAMPFIRE_OFFSET : MIN_CAMPFIRE_OFFSET;
        int maxOffset = profile.rampart() ? rampartInnerClear + MAX_CAMPFIRE_OFFSET : MAX_CAMPFIRE_OFFSET;
        int preferredOffset = minOffset + Math.floorMod(decorativeHash(wallPos.x(), wallPos.z(), 101), (maxOffset - minOffset) + 1);
        for (int attempt = 0; attempt <= maxOffset - minOffset; attempt++) {
            int offset = minOffset + Math.floorMod((preferredOffset - minOffset) + attempt, (maxOffset - minOffset) + 1);
            if (profile.rampart() && hasLadderBetween(level, wallPos, inside, offset)) {
                continue;
            }
            int x = wallPos.x() + (inside.getStepX() * offset);
            int z = wallPos.z() + (inside.getStepZ() * offset);
            if (hasSurfaceWater(level, new GridPos(x, z))) {
                continue;
            }
            Optional<BlockPos> target = campfireTarget(level, x, z);
            if (target.isPresent()) {
                return Optional.of(new CampfireCandidate(new GridPos(x, z), target.get(), inside.getOpposite()));
            }
        }
        return Optional.empty();
    }

    private static boolean hasLadderBetween(ServerLevel level, GridPos wallPos, Direction inside, int offset) {
        for (int distance = 1; distance <= offset + 1; distance++) {
            int x = wallPos.x() + (inside.getStepX() * distance);
            int z = wallPos.z() + (inside.getStepZ() * distance);
            int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + MAX_VISIBLE_WALL_HEIGHT + 2;
            int bottomY = Math.max(level.getMinBuildHeight(), topY - (MAX_VISIBLE_WALL_HEIGHT * 2) - 4);
            for (int y = bottomY; y <= topY; y++) {
                if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.LADDER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<GridPos> campfireLightTargets(ServerLevel level, List<GridPos> perimeter, Set<GridPos> footprint, WallProfile profile) {
        Set<GridPos> targets = new HashSet<>();
        int rampartInnerClear = profile.style().thickness() / 2 + 1;
        int minDepth = profile.rampart() ? rampartInnerClear + MIN_CAMPFIRE_OFFSET : MIN_CAMPFIRE_OFFSET;
        int maxDepth = profile.rampart() ? rampartInnerClear + CAMPFIRE_TARGET_DEPTH : CAMPFIRE_TARGET_DEPTH;
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            Direction along = directionFromDelta(b.x() - a.x(), b.z() - a.z());
            Direction normal = along.getClockWise();
            for (GridPos wallPos : rasterLine(a, b)) {
                Direction inside = insideDirection(wallPos, normal, footprint);
                for (int depth = minDepth; depth <= maxDepth; depth += 2) {
                    int x = wallPos.x() + (inside.getStepX() * depth);
                    int z = wallPos.z() + (inside.getStepZ() * depth);
                    if (hasSurfaceWater(level, new GridPos(x, z))) {
                        continue;
                    }
                    BlockPos surface = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    if (level.getBrightness(LightLayer.BLOCK, surface) < TARGET_CAMPFIRE_LIGHT) {
                        targets.add(new GridPos(x, z));
                    }
                }
            }
        }
        return targets;
    }

    private static int lightGain(CampfireCandidate candidate, Set<GridPos> unlitTargets) {
        int gain = 0;
        for (GridPos target : unlitTargets) {
            if (approximateCampfireLight(candidate.gridPos(), target) >= TARGET_CAMPFIRE_LIGHT) {
                gain++;
            }
        }
        return gain;
    }

    private static int approximateCampfireLight(GridPos source, GridPos target) {
        int distance = Math.abs(source.x() - target.x()) + Math.abs(source.z() - target.z());
        return Math.max(0, CAMPFIRE_LIGHT_LEVEL - distance);
    }

    private Optional<Integer> preferredDoorIndex(ServerLevel level, TerrainSampler sampler, List<GridPos> line, Direction along) {
        return closestPathHit(level, sampler, line, along).map(PathHit::index);
    }

    private Optional<PathHit> closestPathHit(ServerLevel level, TerrainSampler sampler, List<GridPos> line, Direction along) {
        if (line.size() < 3) {
            return Optional.empty();
        }

        Direction normal = along.getClockWise();
        Optional<PathHit> best = Optional.empty();
        for (int i = 1; i < line.size() - 1; i++) {
            GridPos wall = line.get(i);
            for (Direction scanDirection : List.of(normal, normal.getOpposite())) {
                for (int distance = 1; distance <= PATH_GATE_SCAN_DEPTH; distance++) {
                    int x = wall.x() + (scanDirection.getStepX() * distance);
                    int z = wall.z() + (scanDirection.getStepZ() * distance);
                    if (!isVillagePath(level, sampler, x, z)) {
                        continue;
                    }

                    PathHit hit = new PathHit(i, distance);
                    if (best.isEmpty() || hit.distance() < best.get().distance()
                            || hit.distance() == best.get().distance() && centerDistance(i, line.size()) < centerDistance(best.get().index(), line.size())) {
                        best = Optional.of(hit);
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static boolean isVillagePath(ServerLevel level, TerrainSampler sampler, int x, int z) {
        int y = sampler.surfaceY(x, z) - 1;
        return level.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.DIRT_PATH;
    }

    private static int centerDistance(int index, int size) {
        return Math.abs(index - (size / 2));
    }

    private record PathHit(int index, int distance) {
    }

    private static boolean shouldPlaceVines(GridPos p, int index, int lineSize, boolean segmentHasDoor) {
        return !segmentHasDoor
                && index > 2
                && index < lineSize - 3
                && jitteredIntervalHit(p, index, VINE_SPACING, 17);
    }

    private static boolean jitteredIntervalHit(GridPos p, int index, int spacing, int salt) {
        int bucket = Math.floorDiv(index, spacing);
        int target = (spacing / 2) + Math.floorMod(decorativeHash(p.x() + bucket, p.z() - bucket, salt), 5) - 2;
        return Math.floorMod(index, spacing) == Math.max(2, Math.min(spacing - 3, target));
    }

    private void placeVines(ServerLevel level, GridPos wallPos, Direction normal, int minY, int topY) {
        Direction side = decorativeHash(wallPos.x(), wallPos.z(), 23) % 3 == 0 ? normal.getOpposite() : normal;
        BlockPos vineBase = new BlockPos(wallPos.x(), Math.min(topY - 1, minY + 1), wallPos.z()).relative(side);
        int length = 1 + Math.floorMod(decorativeHash(wallPos.x(), wallPos.z(), 53), 3);
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(side.getOpposite()), true);
        for (int dy = 0; dy < length && vineBase.getY() - dy >= minY; dy++) {
            level.setBlock(vineBase.below(dy), vine, Block.UPDATE_ALL);
        }
    }

    private void placeCampfire(ServerLevel level, CampfireCandidate candidate) {
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, candidate.facing());
        level.setBlock(candidate.pos(), campfire, Block.UPDATE_ALL);
    }

    private static Optional<BlockPos> campfireTarget(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY + 1; y >= surfaceY - 1; y--) {
            BlockPos target = new BlockPos(x, y, z);
            BlockPos support = target.below();
            BlockState targetState = level.getBlockState(target);
            BlockState supportState = level.getBlockState(support);
            if (!isWaterOrFluid(targetState)
                    && !isWaterOrFluid(supportState)
                    && canReplaceWithCampfire(targetState)
                    && supportState.isFaceSturdy(level, support, Direction.UP)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    private static boolean canReplaceWithCampfire(BlockState state) {
        return state.isAir()
                || state.is(Blocks.SNOW)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(BlockTags.FLOWERS);
    }

    private record CampfireCandidate(GridPos gridPos, BlockPos pos, Direction facing) {
    }

    private static BlockState stoneBrickState(GridPos p, int y) {
        int value = Math.floorMod(decorativeHash(p.x(), p.z() + y, 11), 12);
        if (value < 2) {
            return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        }
        if (value < 4) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static int decorativeHash(int x, int z, int salt) {
        int h = x * 73428767 ^ z * 91227153 ^ salt * 42349;
        h ^= h >>> 16;
        return h;
    }

    public int enclosureTopY(List<GridPos> perimeter, TerrainSampler sampler, int nominalHeight) {
        List<Integer> terrain = new ArrayList<>();
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            for (GridPos p : rasterLine(a, b)) {
                terrain.add(sampler.surfaceY(p.x(), p.z()));
            }
        }
        if (terrain.isEmpty()) {
            return nominalHeight - 1;
        }
        terrain.sort(Integer::compareTo);
        int index = Math.min(terrain.size() - 1, (int) Math.floor(terrain.size() * 0.75));
        return terrain.get(index) + nominalHeight - 1;
    }

    public List<Integer> computeSegmentTopProfile(List<GridPos> line, TerrainSampler sampler, int nominalHeight, int medianRadius, int maxStep) {
        List<Integer> rawTops = new ArrayList<>();
        if (line.isEmpty()) {
            return rawTops;
        }
        for (GridPos p : line) {
            rawTops.add(sampler.surfaceY(p.x(), p.z()) + nominalHeight - 1);
        }
        return clampStepDeltas(medianFilter(rawTops, medianRadius), maxStep);
    }

    public List<Integer> medianFilter(List<Integer> values, int radius) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            List<Integer> window = new ArrayList<>();
            int start = Math.max(0, i - radius);
            int end = Math.min(values.size() - 1, i + radius);
            for (int j = start; j <= end; j++) {
                window.add(values.get(j));
            }
            window.sort(Integer::compareTo);
            out.add(window.get(window.size() / 2));
        }
        return out;
    }

    public List<Integer> clampStepDeltas(List<Integer> values, int maxStep) {
        List<Integer> out = new ArrayList<>(values);
        for (int i = 1; i < out.size(); i++) {
            out.set(i, clamp(out.get(i), out.get(i - 1) - maxStep, out.get(i - 1) + maxStep));
        }
        for (int i = out.size() - 2; i >= 0; i--) {
            out.set(i, clamp(out.get(i), out.get(i + 1) - maxStep, out.get(i + 1) + maxStep));
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static GridPos offsetForThickness(GridPos p, GridPos a, GridPos b, int t) {
        int dx = Integer.compare(b.x(), a.x());
        int dz = Integer.compare(b.z(), a.z());
        return new GridPos(p.x() - (dz * t), p.z() + (dx * t));
    }

    private static GridPos offsetToward(GridPos p, Direction direction, int distance) {
        return new GridPos(p.x() + (direction.getStepX() * distance), p.z() + (direction.getStepZ() * distance));
    }

    private static GridPos rampartOffset(GridPos p, Direction inside, int lane, int thickness) {
        int half = thickness / 2;
        return offsetToward(p, inside, lane - half);
    }

    private static boolean isRampartInteriorLane(int lane, int thickness) {
        return lane == (thickness / 2);
    }

    private static BlockState rampartParapetState(GridPos p, int index) {
        if (jitteredIntervalHit(p, index, 5, 149)) {
            return Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState();
        }
        return Blocks.STONE_BRICK_WALL.defaultBlockState();
    }

    private static Direction directionFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void placeDoubleDoor(ServerLevel level, GridPos p, GridPos a, GridPos b, Direction inside, TerrainSampler sampler, BlockState threshold, int thickness) {
        Direction along = directionFromDelta(b.x() - a.x(), b.z() - a.z());
        Direction facing = inside.getOpposite();

        BlockPos first = new BlockPos(p.x(), sampler.surfaceY(p.x(), p.z()), p.z());
        BlockPos second = first.relative(along);
        second = new BlockPos(second.getX(), sampler.surfaceY(second.getX(), second.getZ()), second.getZ());
        BlockPos left = along == facing.getClockWise() ? first : second;
        BlockPos right = along == facing.getClockWise() ? second : first;
        int gateY = Math.max(left.getY(), right.getY());
        left = new BlockPos(left.getX(), gateY, left.getZ());
        right = new BlockPos(right.getX(), gateY, right.getZ());

        clearGatePassage(level, left, right, inside, thickness);
        buildThreshold(level, left, right, threshold);
        decorateGate(level, left, right, along, facing);

        BlockState lowerLeft = Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        BlockState upperLeft = lowerLeft.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState lowerRight = lowerLeft.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        BlockState upperRight = lowerRight.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        level.setBlock(left, lowerLeft, Block.UPDATE_ALL);
        level.setBlock(left.above(), upperLeft, Block.UPDATE_ALL);
        level.setBlock(right, lowerRight, Block.UPDATE_ALL);
        level.setBlock(right.above(), upperRight, Block.UPDATE_ALL);
    }

    private static void clearGatePassage(ServerLevel level, BlockPos left, BlockPos right, Direction inside, int thickness) {
        Direction along = horizontalDirection(left, right);
        if (along == null) {
            return;
        }
        BlockPos start = left.relative(along).equals(right) ? left : right;
        int baseY = Math.min(left.getY(), right.getY());
        int half = thickness / 2;
        for (int width = 0; width < 2; width++) {
            BlockPos widthPos = start.relative(along, width);
            for (int depth = -half; depth <= half; depth++) {
                BlockPos tunnel = widthPos.relative(inside, depth);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(new BlockPos(tunnel.getX(), baseY + dy, tunnel.getZ()), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void decorateGate(ServerLevel level, BlockPos left, BlockPos right, Direction along, Direction facing) {
        BlockPos leftPost = left.relative(along.getOpposite());
        BlockPos rightPost = right.relative(along);
        placeGatePost(level, leftPost);
        placeGatePost(level, rightPost);

        BlockPos headerStart = leftPost.above(3);
        for (int i = 0; i <= 3; i++) {
            BlockPos pos = headerStart.relative(along, i);
            BlockState state = i == 1 || i == 2 ? Blocks.SMOOTH_STONE_SLAB.defaultBlockState() : Blocks.STONE_BRICK_SLAB.defaultBlockState();
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }

        level.setBlock(left.above(2), gateBarsState(along), Block.UPDATE_ALL);
        level.setBlock(right.above(2), gateBarsState(along), Block.UPDATE_ALL);
        placeGateApron(level, left, right, along, facing);
    }

    private static BlockState gateBarsState(Direction along) {
        BlockState state = Blocks.IRON_BARS.defaultBlockState();
        if (along.getAxis() == Direction.Axis.X) {
            return state.setValue(BlockStateProperties.EAST, true)
                    .setValue(BlockStateProperties.WEST, true);
        }
        return state.setValue(BlockStateProperties.NORTH, true)
                .setValue(BlockStateProperties.SOUTH, true);
    }

    private static void placeGatePost(ServerLevel level, BlockPos base) {
        for (int dy = 0; dy <= 2; dy++) {
            BlockState state = dy == 1 ? Blocks.ANDESITE.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
            level.setBlock(base.above(dy), state, Block.UPDATE_ALL);
        }
    }

    private static void placeGateApron(ServerLevel level, BlockPos left, BlockPos right, Direction along, Direction facing) {
        BlockPos start = left.relative(along.getOpposite());
        for (int width = 0; width <= 3; width++) {
            BlockPos widthBase = start.relative(along, width);
            for (int depth = -GATE_APRON_DEPTH; depth <= GATE_APRON_DEPTH; depth++) {
                BlockPos sample = widthBase.relative(facing, depth);
                GridPos grid = new GridPos(sample.getX(), sample.getZ());
                if (hasSurfaceWater(level, grid)) {
                    continue;
                }
                int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample.getX(), sample.getZ()) - 1;
                BlockPos ground = new BlockPos(sample.getX(), groundY, sample.getZ());
                BlockState state = level.getBlockState(ground);
                if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)) {
                    level.setBlock(ground, gateApronState(grid, depth), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static BlockState gateApronState(GridPos pos, int depth) {
        int value = Math.floorMod(decorativeHash(pos.x(), pos.z() + depth, 131), 8);
        if (value == 0) {
            return Blocks.ROOTED_DIRT.defaultBlockState();
        }
        if (value <= 2) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        return Blocks.GRAVEL.defaultBlockState();
    }

    private static void buildThreshold(ServerLevel level, BlockPos left, BlockPos right, BlockState threshold) {
        int minX = Math.min(left.getX(), right.getX());
        int maxX = Math.max(left.getX(), right.getX());
        int minZ = Math.min(left.getZ(), right.getZ());
        int maxZ = Math.max(left.getZ(), right.getZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos pos = new BlockPos(x, left.getY() - 1, z);
                level.setBlock(pos, threshold, Block.UPDATE_ALL);
            }
        }
    }

    private static void clearForDoor(ServerLevel level, BlockPos pos) {
        for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(pos.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static Direction facingFromSegment(GridPos a, GridPos b) {
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return dz >= 0 ? Direction.WEST : Direction.EAST;
    }

    private static BlockState blockState(String id) {
        Optional<Block> block = Optional.ofNullable(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)));
        return block.orElse(Blocks.COBBLESTONE).defaultBlockState();
    }

    public static List<GridPos> rasterLine(GridPos a, GridPos b) {
        List<GridPos> out = new ArrayList<>();
        int x0 = a.x();
        int z0 = a.z();
        int x1 = b.x();
        int z1 = b.z();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        while (true) {
            out.add(new GridPos(x0, z0));
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
        return out;
    }
}
