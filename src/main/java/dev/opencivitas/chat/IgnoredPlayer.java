package dev.opencivitas.chat;

import java.time.Instant;
import java.util.UUID;

public record IgnoredPlayer(UUID playerId, String playerName, Instant ignoredAt) {
}
