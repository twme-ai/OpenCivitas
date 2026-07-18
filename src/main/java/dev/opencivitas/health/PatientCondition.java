package dev.opencivitas.health;

import java.time.Instant;
import java.util.UUID;

public record PatientCondition(
        long id,
        UUID patientId,
        String conditionId,
        String source,
        Instant acquiredAt,
        Instant resolvedAt
) {
}
