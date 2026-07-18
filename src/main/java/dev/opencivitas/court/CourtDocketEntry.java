package dev.opencivitas.court;

import java.time.Instant;

public record CourtDocketEntry(
        long id,
        String actorName,
        String type,
        String text,
        Instant createdAt
) {
}
