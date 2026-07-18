package dev.opencivitas.citizen;

import java.time.Instant;
import java.util.UUID;

public record CitizenProfile(
        UUID uuid,
        String lastName,
        Instant joinedAt,
        Instant lastSeenAt,
        String preferredLocale,
        long balanceCents
) {
}
