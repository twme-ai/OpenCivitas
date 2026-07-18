package dev.opencivitas.property;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record Property(
        long id,
        String plotId,
        String worldName,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ,
        Long salePriceCents,
        Long rentPriceCents,
        long rentDurationMillis,
        UUID titleholderId,
        String titleholderName,
        UUID tenantId,
        String tenantName,
        Instant rentalStartedAt,
        Instant rentalEndsAt,
        long rentPaidCents,
        Set<UUID> trusted,
        Instant createdAt
) {
    public Property {
        trusted = Set.copyOf(trusted);
    }

    public boolean contains(String world, int x, int y, int z) {
        return worldName.equals(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean canBuild(UUID player) {
        return player.equals(titleholderId) || player.equals(tenantId) || trusted.contains(player);
    }

    public boolean canManage(UUID player) {
        return player.equals(titleholderId) || player.equals(tenantId);
    }

    public boolean availableToBuy() {
        return salePriceCents != null && titleholderId == null && tenantId == null;
    }

    public boolean availableToRent() {
        return rentPriceCents != null && tenantId == null;
    }

    public long volume() {
        return Math.multiplyExact(
                Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
                (long) maxZ - minZ + 1);
    }
}
