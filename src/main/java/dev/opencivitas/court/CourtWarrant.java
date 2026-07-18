package dev.opencivitas.court;

import java.time.Instant;
import java.util.UUID;

public record CourtWarrant(
        long id,
        long caseId,
        String judgeName,
        UUID targetId,
        String targetName,
        WarrantType type,
        String reason,
        Instant issuedAt,
        Instant expiresAt,
        String status
) {
}
