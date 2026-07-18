package dev.opencivitas.police;

import java.time.Instant;

public record PoliceCharge(
        long id,
        Long reportId,
        String suspectName,
        String officerName,
        String offenseId,
        String reason,
        long fineCents,
        int jailMinutes,
        ChargeStatus status,
        Instant chargedAt,
        Instant resolvedAt,
        String resolution
) {
}
