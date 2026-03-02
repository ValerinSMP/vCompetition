package com.valerinsmp.vcompetition.model;

import org.bukkit.Location;

import java.util.Objects;

public final class BlockKey {
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    public BlockKey(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockKey fromLocation(Location location) {
        return new BlockKey(
                Objects.requireNonNull(location.getWorld(), "World no puede ser null").getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockKey blockKey)) {
            return false;
        }
        return x == blockKey.x && y == blockKey.y && z == blockKey.z && world.equals(blockKey.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }
}
