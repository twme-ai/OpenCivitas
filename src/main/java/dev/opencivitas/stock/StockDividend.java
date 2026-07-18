package dev.opencivitas.stock;

import java.time.Instant;

public record StockDividend(
        long id,
        String symbol,
        long perShareCents,
        long totalCents,
        int recipientCount,
        long paidShares,
        Instant paidAt
) { }
