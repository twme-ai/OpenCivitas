package dev.opencivitas.security;

import java.util.UUID;

public record SecurityComputer(
        long id,
        UUID ownerId,
        String name,
        String world,
        int x,
        int y,
        int z,
        Long groupId,
        boolean publicAccess,
        long createdAt
) {
    public boolean at(String world, int x, int y, int z) {
        return this.world.equals(world) && this.x == x && this.y == y && this.z == z;
    }
}
