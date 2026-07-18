package dev.opencivitas.election;

import java.time.Instant;
import java.util.UUID;

public record ElectionChoice(
        String id,
        String displayName,
        UUID candidateId,
        UUID runningMateId,
        String runningMateName,
        Instant nominatedAt,
        boolean active
) {
}
