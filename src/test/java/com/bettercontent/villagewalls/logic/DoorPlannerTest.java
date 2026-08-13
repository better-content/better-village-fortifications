package com.bettercontent.villagewalls.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoorPlannerTest {
    @Test
    void picksFlattestSegmentsWithSpacing() {
        List<SegmentFlatness> flatness = List.of(
                new SegmentFlatness(0, 3),
                new SegmentFlatness(1, 1),
                new SegmentFlatness(2, 1),
                new SegmentFlatness(3, 2),
                new SegmentFlatness(4, 1)
        );
        List<Integer> chosen = DoorPlanner.chooseDoorSegments(flatness, 2);
        assertEquals(List.of(1, 4), chosen);
    }

    @Test
    void emptyWhenNoSegments() {
        assertEquals(List.of(), DoorPlanner.chooseDoorSegments(List.of(), 5));
    }

    @Test
    void emptyWhenDoorCapIsZero() {
        List<SegmentFlatness> flatness = List.of(new SegmentFlatness(1, 1));
        assertEquals(List.of(), DoorPlanner.chooseDoorSegments(flatness, 0));
    }
}
