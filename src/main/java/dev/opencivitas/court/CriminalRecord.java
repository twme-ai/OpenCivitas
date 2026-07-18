package dev.opencivitas.court;

import java.time.Instant;

public record CriminalRecord(
        long id,
        long caseId,
        String charge,
        long fineCents,
        int jailMinutes,
        Instant convictedAt,
        Instant pardonedAt
) {
}
