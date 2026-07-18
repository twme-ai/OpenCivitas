package dev.opencivitas.claim;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record LandClaim(
        long id,
        UUID ownerId,
        String ownerName,
        String worldName,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        boolean explosions,
        Set<UUID> trusted,
        Instant createdAt
) {
    public LandClaim {
        trusted = Set.copyOf(trusted);
    }

    public boolean contains(String world, int x, int z) {
        return worldName.equals(world) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean canBuild(UUID player) {
        return ownerId.equals(player) || trusted.contains(player);
    }

    public int area() {
        return Math.multiplyExact(Math.addExact(Math.subtractExact(maxX, minX), 1),
                Math.addExact(Math.subtractExact(maxZ, minZ), 1));
    }

    public boolean isCorner(int x, int z) {
        return (x == minX || x == maxX) && (z == minZ || z == maxZ);
    }
}
