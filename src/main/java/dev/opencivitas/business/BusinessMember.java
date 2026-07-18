package dev.opencivitas.business;

import java.time.Instant;
import java.util.UUID;

public record BusinessMember(
        UUID playerId,
        String playerName,
        BusinessRole role,
        long wageCents,
        Instant joinedAt
) {
}
