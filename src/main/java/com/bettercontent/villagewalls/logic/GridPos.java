package com.bettercontent.villagewalls.logic;

public record GridPos(int x, int z) {
    public GridPos add(int dx, int dz) {
        return new GridPos(x + dx, z + dz);
    }
}
