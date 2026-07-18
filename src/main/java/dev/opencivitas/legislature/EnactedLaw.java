package dev.opencivitas.legislature;

import java.time.Instant;

public record EnactedLaw(
        long id,
        long billId,
        String number,
        String title,
        String body,
        BillType type,
        Instant enactedAt
) {
}
