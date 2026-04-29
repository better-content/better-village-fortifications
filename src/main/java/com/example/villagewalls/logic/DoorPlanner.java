package com.example.villagewalls.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DoorPlanner {
    private DoorPlanner() {
    }

    public static List<Integer> chooseDoorSegments(List<SegmentFlatness> flatness, int maxDoors) {
        if (flatness.isEmpty() || maxDoors <= 0) {
            return List.of();
        }
        List<SegmentFlatness> sorted = new ArrayList<>(flatness);
        sorted.sort(Comparator.comparingInt(SegmentFlatness::score).thenComparingInt(SegmentFlatness::index));
        int best = sorted.get(0).score();

        Map<Integer, List<SegmentFlatness>> byScore = sorted.stream().collect(Collectors.groupingBy(SegmentFlatness::score));
        List<SegmentFlatness> equallyFlat = byScore.get(best).stream()
                .sorted(Comparator.comparingInt(SegmentFlatness::index))
                .toList();

        return equallyFlat.stream().limit(maxDoors).map(SegmentFlatness::index).toList();
    }
}
