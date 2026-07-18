package dev.opencivitas.stock;

import java.time.Instant;
import java.util.UUID;

public record StockTrade(
        long id,
        String symbol,
        long buyOrderId,
        long sellOrderId,
        UUID buyerId,
        String buyerName,
        UUID sellerId,
        String sellerName,
        String sellerBusiness,
        long quantity,
        long priceCents,
        long buyerFeeCents,
        long sellerFeeCents,
        Instant executedAt
) { }
