package dev.opencivitas.shop;

import java.time.Instant;
import java.util.UUID;

public record ShopSale(
        long id,
        long shopId,
        UUID customerId,
        String customerName,
        ShopDirection direction,
        String itemKey,
        int itemAmount,
        long totalCents,
        ShopOwnerType ownerType,
        UUID ownerId,
        String accountName,
        String businessSlug,
        String worldName,
        int signX,
        int signY,
        int signZ,
        Instant createdAt
) {
}
