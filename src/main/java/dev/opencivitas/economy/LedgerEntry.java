package dev.opencivitas.economy;

import java.time.Instant;

public record LedgerEntry(
        long id,
        long amountCents,
        LedgerEntryType type,
        String counterpartyName,
        Instant createdAt
) {
}
