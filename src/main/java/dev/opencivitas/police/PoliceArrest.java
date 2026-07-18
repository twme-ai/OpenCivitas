package dev.opencivitas.police;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PoliceArrest(
        long id,
        UUID suspectId,
        String suspectName,
        String officerName,
        String reason,
        long fineAssessedCents,
        long fineCollectedCents,
        int jailMinutes,
        Instant arrestedAt,
        Instant releaseAt,
        Instant releasedAt,
        ArrestStatus status,
        List<Long> chargeIds,
        List<String> offenseIds,
        List<Long> warrantIds
) {
    public PoliceArrest {
        chargeIds = List.copyOf(chargeIds);
        offenseIds = List.copyOf(offenseIds);
        warrantIds = List.copyOf(warrantIds);
    }
}
