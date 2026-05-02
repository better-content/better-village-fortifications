package com.example.villagewalls.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DoorPlanner {
    private DoorPlanner() {
    }

    public static List<Integer> chooseDoorSegments(List<SegmentFlatness> flatness, int maxDoors) {
        if (flatness.isEmpty() || maxDoors <= 0) {
            return List.of();
        }
        List<SegmentFlatness> sorted = new ArrayList<>(flatness);
        sorted.sort(Comparator.comparingInt(SegmentFlatness::score).thenComparingInt(SegmentFlatness::index));

        int segmentCount = flatness.stream().mapToInt(SegmentFlatness::index).max().orElse(0) + 1;
        int minSpacing = Math.max(2, segmentCount / Math.max(1, maxDoors * 2));
        List<Integer> chosen = new ArrayList<>();
        for (SegmentFlatness candidate : sorted) {
            if (chosen.stream().allMatch(index -> cyclicDistance(index, candidate.index(), segmentCount) >= minSpacing)) {
                chosen.add(candidate.index());
            }
            if (chosen.size() >= maxDoors) {
                break;
            }
        }
        chosen.sort(Integer::compareTo);
        return chosen;
    }

    private static int cyclicDistance(int a, int b, int size) {
        int distance = Math.abs(a - b);
        return Math.min(distance, size - distance);
    }
}
