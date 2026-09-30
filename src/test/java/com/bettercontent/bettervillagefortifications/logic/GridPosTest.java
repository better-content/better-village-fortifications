package com.bettercontent.bettervillagefortifications.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GridPosTest {
    @Test
    void addOffsetsCoordinates() {
        GridPos p = new GridPos(3, -2);
        assertEquals(new GridPos(4, 2), p.add(1, 4));
    }
}
