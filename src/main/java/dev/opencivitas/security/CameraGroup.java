package dev.opencivitas.security;

import java.util.UUID;

public record CameraGroup(long id, UUID ownerId, String name, long createdAt) {
}
