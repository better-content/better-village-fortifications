package com.bettercontent.bettervillagefortifications.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VillageOutlineSolver {
    private static final int MAX_SEGMENT_LENGTH = 10;

    private VillageOutlineSolver() {
    }

    public static List<GridPos> traceCleanRing(Set<GridPos> footprint, int bufferRadius) {
        if (footprint.isEmpty()) {
            return List.of();
        }

        Set<GridPos> occupied = bufferedCells(connectFootprint(footprint), Math.max(bufferRadius, 1));
        return splitLongEdges(removeCollinear(largestBoundaryLoop(occupied)));
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

    private static Set<GridPos> connectFootprint(Set<GridPos> footprint) {
        if (footprint.size() <= 1) {
            return footprint;
        }
        int centerX = (int) Math.round(footprint.stream().mapToInt(GridPos::x).average().orElse(0));
        int centerZ = (int) Math.round(footprint.stream().mapToInt(GridPos::z).average().orElse(0));
        GridPos center = new GridPos(centerX, centerZ);
        Set<GridPos> connected = new HashSet<>(footprint);
        for (GridPos point : footprint) {
            connected.addAll(axisLine(center, point));
        }
        return connected;
    }

    private static List<GridPos> axisLine(GridPos a, GridPos b) {
        List<GridPos> out = new ArrayList<>();
        int stepX = Integer.compare(b.x(), a.x());
        for (int x = a.x(); x != b.x(); x += stepX) {
            out.add(new GridPos(x, a.z()));
        }
        int stepZ = Integer.compare(b.z(), a.z());
        for (int z = a.z(); z != b.z(); z += stepZ) {
            out.add(new GridPos(b.x(), z));
        }
        out.add(b);
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

    private static void addEdge(List<GridPos> out, GridPos start, GridPos end) {
        int dx = Integer.compare(end.x(), start.x());
        int dz = Integer.compare(end.z(), start.z());
        int length = Math.abs(end.x() - start.x()) + Math.abs(end.z() - start.z());
        int steps = Math.max(1, (int) Math.ceil(length / (double) MAX_SEGMENT_LENGTH));

        for (int i = 0; i <= steps; i++) {
            int distance = Math.min(length, (int) Math.round(i * (length / (double) steps)));
            GridPos point = new GridPos(start.x() + (dx * distance), start.z() + (dz * distance));
            if (out.isEmpty() || !out.get(out.size() - 1).equals(point)) {
                out.add(point);
            }
        }
        if (out.get(out.size() - 1).equals(out.get(0))) {
            out.remove(out.size() - 1);
        }
    }

    private static List<GridPos> largestBoundaryLoop(Set<GridPos> occupied) {
        Map<Vertex, List<Vertex>> edges = boundaryEdges(occupied);
        Set<Edge> used = new HashSet<>();
        List<Vertex> best = List.of();
        int bestArea = 0;

        List<Vertex> starts = edges.keySet().stream()
                .sorted(Comparator.comparingInt(Vertex::x).thenComparingInt(Vertex::z))
                .toList();
        for (Vertex start : starts) {
            for (Vertex next : edges.getOrDefault(start, List.of())) {
                Edge first = new Edge(start, next);
                if (used.contains(first)) {
                    continue;
                }
                List<Vertex> loop = walkLoop(edges, first, used);
                int area = Math.abs(doubleArea(loop));
                if (area > bestArea) {
                    bestArea = area;
                    best = loop;
                }
            }
        }

        return best.stream().map(vertex -> new GridPos(vertex.x(), vertex.z())).toList();
    }

    private static Map<Vertex, List<Vertex>> boundaryEdges(Set<GridPos> occupied) {
        Map<Vertex, List<Vertex>> edges = new HashMap<>();
        for (GridPos cell : occupied) {
            int x = cell.x();
            int z = cell.z();
            if (!occupied.contains(cell.add(0, -1))) {
                addDirectedEdge(edges, new Vertex(x, z), new Vertex(x + 1, z));
            }
            if (!occupied.contains(cell.add(1, 0))) {
                addDirectedEdge(edges, new Vertex(x + 1, z), new Vertex(x + 1, z + 1));
            }
            if (!occupied.contains(cell.add(0, 1))) {
                addDirectedEdge(edges, new Vertex(x + 1, z + 1), new Vertex(x, z + 1));
            }
            if (!occupied.contains(cell.add(-1, 0))) {
                addDirectedEdge(edges, new Vertex(x, z + 1), new Vertex(x, z));
            }
        }
        return edges;
    }

    private static void addDirectedEdge(Map<Vertex, List<Vertex>> edges, Vertex start, Vertex end) {
        edges.computeIfAbsent(start, ignored -> new ArrayList<>()).add(end);
    }

    private static List<Vertex> walkLoop(Map<Vertex, List<Vertex>> edges, Edge first, Set<Edge> used) {
        List<Vertex> loop = new ArrayList<>();
        Edge current = first;
        while (used.add(current)) {
            loop.add(current.start());
            Vertex nextStart = current.end();
            if (nextStart.equals(first.start())) {
                break;
            }
            List<Vertex> candidates = edges.getOrDefault(nextStart, List.of());
            Vertex nextEnd = candidates.stream()
                    .filter(candidate -> !used.contains(new Edge(nextStart, candidate)))
                    .findFirst()
                    .orElse(first.start());
            current = new Edge(nextStart, nextEnd);
        }
        return loop;
    }

    private static int doubleArea(List<Vertex> loop) {
        int area = 0;
        for (int i = 0; i < loop.size(); i++) {
            Vertex a = loop.get(i);
            Vertex b = loop.get((i + 1) % loop.size());
            area += (a.x() * b.z()) - (b.x() * a.z());
        }
        return area;
    }

    private static List<GridPos> removeCollinear(List<GridPos> points) {
        List<GridPos> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            GridPos prev = points.get(Math.floorMod(i - 1, points.size()));
            GridPos curr = points.get(i);
            GridPos next = points.get((i + 1) % points.size());
            int dx1 = Integer.compare(curr.x() - prev.x(), 0);
            int dz1 = Integer.compare(curr.z() - prev.z(), 0);
            int dx2 = Integer.compare(next.x() - curr.x(), 0);
            int dz2 = Integer.compare(next.z() - curr.z(), 0);
            if (dx1 != dx2 || dz1 != dz2) {
                out.add(curr);
            }
        }
        return out;
    }

    private static List<GridPos> splitLongEdges(List<GridPos> points) {
        List<GridPos> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            addEdge(out, points.get(i), points.get((i + 1) % points.size()));
        }
        return out;
    }

    private record Vertex(int x, int z) {
    }

    private record Edge(Vertex start, Vertex end) {
    }
}
