package dev.opencivitas.vehicle;

import java.time.Instant;
import java.util.UUID;

public record VehicleState(
        UUID id,
        String typeId,
        UUID ownerId,
        String ownerName,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        long fuel,
        boolean locked,
        int health,
        Instant createdAt,
        Instant updatedAt
) {
    public VehicleState withTelemetry(
            String world, double nextX, double nextY, double nextZ, float nextYaw,
            long nextFuel, int nextHealth, Instant nextUpdatedAt
    ) {
        return new VehicleState(id, typeId, ownerId, ownerName, world,
                nextX, nextY, nextZ, nextYaw, nextFuel, locked, nextHealth, createdAt, nextUpdatedAt);
    }

    public VehicleState withLocked(boolean nextLocked, Instant nextUpdatedAt) {
        return new VehicleState(id, typeId, ownerId, ownerName, worldName,
                x, y, z, yaw, fuel, nextLocked, health, createdAt, nextUpdatedAt);
    }
}
