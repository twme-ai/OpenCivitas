package dev.opencivitas.family;

import java.time.Instant;
import java.util.UUID;

public record Friendship(UUID friendId, String friendName, Instant createdAt) {
}
