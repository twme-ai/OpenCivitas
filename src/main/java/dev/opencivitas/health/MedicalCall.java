package dev.opencivitas.health;

import java.time.Instant;
import java.util.UUID;

public record MedicalCall(
        long id,
        UUID patientId,
        String patientName,
        String world,
        double x,
        double y,
        double z,
        MedicalCallStatus status,
        UUID claimedBy,
        Instant createdAt
) {
}
