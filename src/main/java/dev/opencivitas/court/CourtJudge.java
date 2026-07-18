package dev.opencivitas.court;

import java.time.Instant;
import java.util.UUID;

public record CourtJudge(UUID playerId, String playerName, String role, Instant joinedAt) {
}
