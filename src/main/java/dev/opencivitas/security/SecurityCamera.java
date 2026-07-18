package dev.opencivitas.security;

import java.util.UUID;

public record SecurityCamera(
        long id,
        UUID ownerId,
        String name,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long createdAt
) {
}
