package dev.opencivitas.police;

import java.time.Instant;

public record PoliceReportEvent(
        long id,
        String actorName,
        String type,
        String text,
        Instant createdAt
) {
}
