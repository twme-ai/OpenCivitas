package dev.opencivitas.business;

import java.time.Instant;

public record BusinessLedgerEntry(
        long id,
        long amountCents,
        String type,
        String actorName,
        String counterpartyName,
        Instant createdAt
) {
}
