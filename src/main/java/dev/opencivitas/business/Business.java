package dev.opencivitas.business;

import java.time.Instant;
import java.util.UUID;

public record Business(
        long id,
        String slug,
        String displayName,
        UUID proprietorId,
        String proprietorName,
        long balanceCents,
        BusinessStatus status,
        Instant createdAt
) {
}
