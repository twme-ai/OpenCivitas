package dev.opencivitas.legislature;

import java.time.Instant;
import java.util.UUID;

public record LegislativeAmendment(
        long id,
        UUID authorId,
        String authorName,
        String text,
        String status,
        Instant createdAt
) {
}
