package dev.opencivitas.police;

import java.time.Instant;

public record PublicCriminalRecord(
        String reference,
        String charge,
        long fineCents,
        int jailMinutes,
        Instant recordedAt,
        boolean cleared
) {
}
