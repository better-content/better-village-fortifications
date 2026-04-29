package com.example.villagewalls.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VillageOutlineSolver {
    private VillageOutlineSolver() {
    }

    public static Set<GridPos> bufferedCells(Set<GridPos> seeds, int bufferRadius) {
        Set<GridPos> out = new HashSet<>();
        int r2 = bufferRadius * bufferRadius;
        for (GridPos seed : seeds) {
            for (int dx = -bufferRadius; dx <= bufferRadius; dx++) {
                for (int dz = -bufferRadius; dz <= bufferRadius; dz++) {
                    if ((dx * dx) + (dz * dz) <= r2) {
                        out.add(seed.add(dx, dz));
                    }
                }
            }
        }
        return out;
    }

    public static List<GridPos> traceBoundary(Set<GridPos> occupied) {
        Set<GridPos> boundary = new HashSet<>();
        for (GridPos p : occupied) {
            if (!occupied.contains(p.add(1, 0)) || !occupied.contains(p.add(-1, 0))
                    || !occupied.contains(p.add(0, 1)) || !occupied.contains(p.add(0, -1))) {
                boundary.add(p);
            }
        }
        if (boundary.isEmpty()) {
            return List.of();
        }
        GridPos start = boundary.stream().min(Comparator.comparingInt(GridPos::x).thenComparingInt(GridPos::z)).orElseThrow();
        List<GridPos> ordered = new ArrayList<>();
        Set<GridPos> seen = new HashSet<>();
        GridPos curr = start;
        while (true) {
            ordered.add(curr);
            seen.add(curr);
            GridPos next = nearestNeighbor(curr, boundary, seen);
            if (next == null) {
                break;
            }
            curr = next;
        }
        return simplify(ordered, 2);
    }

    private static GridPos nearestNeighbor(GridPos from, Set<GridPos> points, Set<GridPos> seen) {
        return points.stream()
                .filter(p -> !seen.contains(p))
                .min(Comparator.comparingInt(p -> dist2(from, p)))
                .orElse(null);
    }

    private static int dist2(GridPos a, GridPos b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static List<GridPos> simplify(List<GridPos> points, int minStep) {
        if (points.size() <= 2) {
            return points;
        }
        List<GridPos> out = new ArrayList<>();
        GridPos prev = points.get(0);
        out.add(prev);
        for (int i = 1; i < points.size(); i++) {
            GridPos p = points.get(i);
            if (Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z()) >= minStep) {
                out.add(p);
                prev = p;
            }
        }
        return out;
    }
}
