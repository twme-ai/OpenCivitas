package dev.opencivitas.stock;

import java.time.Instant;

public record StockExchange(
        long id,
        String slug,
        String displayName,
        long operatorBusinessId,
        String operatorBusiness,
        int feeBasisPoints,
        StockExchangeStatus status,
        Instant createdAt
) { }
