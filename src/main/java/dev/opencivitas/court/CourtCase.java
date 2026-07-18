package dev.opencivitas.court;

import java.time.Instant;
import java.util.UUID;

public record CourtCase(
        long id,
        String number,
        CourtLevel level,
        CourtCaseType type,
        CourtCaseStatus status,
        Long parentCaseId,
        UUID filerId,
        UUID plaintiffId,
        String plaintiffName,
        UUID defendantId,
        String defendantName,
        String title,
        String claim,
        long claimAmountCents,
        Instant filedAt,
        Instant scheduledAt,
        Instant decidedAt,
        CourtOutcome outcome,
        String decision,
        long judgmentCents,
        long fineCents,
        int jailMinutes
) {
}
