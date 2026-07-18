package dev.opencivitas.health;

import java.time.Instant;
import java.util.UUID;

public record MedicalTreatment(
        long id,
        UUID patientId,
        UUID practitionerId,
        String treatmentId,
        String conditionId,
        Instant administeredAt,
        boolean medicareEligible,
        long medicareBenefitCents,
        Instant billedAt
) {
}
