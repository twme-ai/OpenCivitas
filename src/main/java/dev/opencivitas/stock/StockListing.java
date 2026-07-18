package dev.opencivitas.stock;

import java.time.Instant;

public record StockListing(
        long id,
        String symbol,
        long exchangeId,
        String exchangeName,
        int feeBasisPoints,
        long issuerBusinessId,
        String issuerBusiness,
        long authorizedShares,
        long treasuryShares,
        StockListingStatus status,
        long initialPriceCents,
        long lastPriceCents,
        Instant lastTradeAt,
        Instant appliedAt,
        Instant approvedAt
) { }
