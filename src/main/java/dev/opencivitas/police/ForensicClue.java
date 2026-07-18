package dev.opencivitas.police;

import java.time.Instant;

public record ForensicClue(
        long id,
        CombatIncident incident,
        String status,
        String collectorName,
        Instant createdAt,
        Instant collectedAt
) {
}
