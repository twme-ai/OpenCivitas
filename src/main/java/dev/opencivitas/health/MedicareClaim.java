package dev.opencivitas.health;

public record MedicareClaim(
        long treatmentRecordId,
        String treatmentId,
        String patientName,
        long benefitCents
) {
}
