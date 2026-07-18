package dev.opencivitas.stock;

import java.time.Instant;
import java.util.UUID;

public record StockOrder(
        long id,
        String symbol,
        UUID ownerId,
        String ownerName,
        boolean issuerSale,
        StockOrderSide side,
        long limitPriceCents,
        long originalQuantity,
        long remainingQuantity,
        long escrowCents,
        StockOrderStatus status,
        Instant createdAt,
        Instant closedAt
) { }
