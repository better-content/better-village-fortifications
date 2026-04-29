package com.example.villagewalls.world;

import com.example.villagewalls.config.WallStyle;
import com.example.villagewalls.logic.DoorPlanner;
import com.example.villagewalls.logic.GridPos;
import com.example.villagewalls.logic.SegmentFlatness;
import com.example.villagewalls.logic.VillageOutlineSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class VillageWallGenerator {
    public record Result(int villagePoints, int perimeterPoints, int doorsPlaced) {
    }

    public Result generate(ServerLevel level, BlockPos origin, int villagerSearchRadius, int bufferRadius, WallStyle style, int maxDoors) {
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class,
                new net.minecraft.world.phys.AABB(origin).inflate(villagerSearchRadius));
        Set<GridPos> villageSeeds = new HashSet<>();
        for (Villager villager : villagers) {
            villageSeeds.add(new GridPos(villager.blockPosition().getX(), villager.blockPosition().getZ()));
        }
        if (villageSeeds.isEmpty()) {
            return new Result(0, 0, 0);
        }

        Set<GridPos> buffered = VillageOutlineSolver.bufferedCells(villageSeeds, bufferRadius);
        List<GridPos> perimeter = VillageOutlineSolver.traceBoundary(buffered);
        if (perimeter.size() < 4) {
            return new Result(villageSeeds.size(), perimeter.size(), 0);
        }

        TerrainSampler sampler = (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        List<SegmentFlatness> segmentFlatness = scoreSegments(perimeter, sampler);
        List<Integer> doorSegments = DoorPlanner.chooseDoorSegments(segmentFlatness, maxDoors);

        BlockState primary = blockState(style.primaryBlock());
        BlockState accent = blockState(style.accentBlock());

        int doorCount = 0;
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            boolean placeDoor = doorSegments.contains(i);
            if (placeDoor) {
                doorCount++;
            }
            placeSegment(level, a, b, sampler, style, primary, accent, placeDoor);
        }
        return new Result(villageSeeds.size(), perimeter.size(), doorCount * 2);
    }

    public List<SegmentFlatness> scoreSegments(List<GridPos> perimeter, TerrainSampler sampler) {
        List<SegmentFlatness> out = new ArrayList<>();
        for (int i = 0; i < perimeter.size(); i++) {
            GridPos a = perimeter.get(i);
            GridPos b = perimeter.get((i + 1) % perimeter.size());
            int score = segmentFlatnessScore(a, b, sampler);
            out.add(new SegmentFlatness(i, score));
        }
        return out;
    }

    public int segmentFlatnessScore(GridPos a, GridPos b, TerrainSampler sampler) {
        List<GridPos> line = rasterLine(a, b);
        if (line.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (GridPos p : line) {
            int y = sampler.surfaceY(p.x(), p.z());
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return max - min;
    }

    private void placeSegment(ServerLevel level, GridPos a, GridPos b, TerrainSampler sampler, WallStyle style, BlockState primary, BlockState accent, boolean placeDoor) {
        List<GridPos> line = rasterLine(a, b);
        if (line.isEmpty()) {
            return;
        }
        int mid = line.size() / 2;
        for (int i = 0; i < line.size(); i++) {
            GridPos p = line.get(i);
            int y = sampler.surfaceY(p.x(), p.z());
            boolean doorBand = placeDoor && (i == mid || i == mid + 1);
            if (doorBand) {
                placeDoubleDoor(level, p, y, a, b);
                continue;
            }
            for (int t = 0; t < style.thickness(); t++) {
                GridPos offset = offsetForThickness(p, a, b, t);
                int oy = sampler.surfaceY(offset.x(), offset.z());
                for (int h = 0; h < style.height(); h++) {
                    boolean isAccent = (i % style.accentEvery() == 0) && h == style.height() - 1;
                    BlockPos pos = new BlockPos(offset.x(), oy + h, offset.z());
                    level.setBlock(pos, isAccent ? accent : primary, Block.UPDATE_ALL);
                }
                if (style.walkable()) {
                    BlockPos top = new BlockPos(offset.x(), oy + style.height(), offset.z());
                    level.setBlock(top, primary, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static GridPos offsetForThickness(GridPos p, GridPos a, GridPos b, int t) {
        int dx = Integer.compare(b.x(), a.x());
        int dz = Integer.compare(b.z(), a.z());
        return new GridPos(p.x() - (dz * t), p.z() + (dx * t));
    }

    private void placeDoubleDoor(ServerLevel level, GridPos p, int y, GridPos a, GridPos b) {
        Direction facing = facingFromSegment(a, b);
        BlockState lower = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState lowerRight = lower.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        BlockState upperRight = upper.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);

        BlockPos left = new BlockPos(p.x(), y, p.z());
        BlockPos right = left.relative(facing.getClockWise());

        clearForDoor(level, left);
        clearForDoor(level, right);

        level.setBlock(left, lower, Block.UPDATE_ALL);
        level.setBlock(left.above(), upper, Block.UPDATE_ALL);
        level.setBlock(right, lowerRight, Block.UPDATE_ALL);
        level.setBlock(right.above(), upperRight, Block.UPDATE_ALL);
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
