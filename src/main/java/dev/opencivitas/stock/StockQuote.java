package dev.opencivitas.stock;

public record StockQuote(
        StockListing listing,
        Long bestBidCents,
        Long bestAskCents,
        long investorShares,
        int shareholderCount
) { }
