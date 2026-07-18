package dev.opencivitas.legislature;

import java.time.Instant;

public record LegislativeEvent(
        long id,
        String actorName,
        String type,
        String detail,
        Instant createdAt
) {
}
