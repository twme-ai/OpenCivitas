package dev.opencivitas.court;

import java.time.Instant;

public record CourtOrder(
        long id,
        String judgeName,
        String targetName,
        String type,
        String text,
        Instant issuedAt,
        Instant expiresAt,
        String status
) {
}
