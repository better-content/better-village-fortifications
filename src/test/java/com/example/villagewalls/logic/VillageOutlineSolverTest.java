package com.example.villagewalls.logic;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VillageOutlineSolverTest {
    @Test
    void bufferedCellsExpandsInput() {
        Set<GridPos> seeds = Set.of(new GridPos(0, 0));
        Set<GridPos> buffered = VillageOutlineSolver.bufferedCells(seeds, 2);
        assertTrue(buffered.contains(new GridPos(0, 0)));
        assertTrue(buffered.contains(new GridPos(2, 0)));
        assertFalse(buffered.contains(new GridPos(2, 2)));
    }

    @Test
    void traceBoundaryFindsOuterRing() {
        Set<GridPos> occupied = new HashSet<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                occupied.add(new GridPos(x, z));
            }
        }
        List<GridPos> boundary = VillageOutlineSolver.traceBoundary(occupied);
        assertFalse(boundary.isEmpty());
        assertTrue(boundary.stream().anyMatch(p -> p.x() == 0));
        assertTrue(boundary.stream().anyMatch(p -> p.x() == 4));
        assertTrue(boundary.stream().anyMatch(p -> p.z() == 0));
        assertTrue(boundary.stream().anyMatch(p -> p.z() == 4));
    }

    @Test
    void traceBoundaryEmptyForNoCells() {
        assertEquals(List.of(), VillageOutlineSolver.traceBoundary(Set.of()));
    }

    @Test
    void traceBoundarySingleCellWorks() {
        List<GridPos> boundary = VillageOutlineSolver.traceBoundary(Set.of(new GridPos(2, 3)));
        assertEquals(1, boundary.size());
        assertEquals(new GridPos(2, 3), boundary.get(0));
    }
}
