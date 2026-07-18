package dev.opencivitas.legislature;

import java.time.Instant;
import java.util.UUID;

public record LegislativeBill(
        long id,
        String number,
        BillType type,
        String title,
        String body,
        UUID authorId,
        String authorName,
        BillStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant presidentialDeadline,
        String vetoReason,
        Long referendumElectionId,
        Instant enactedAt
) {
}
