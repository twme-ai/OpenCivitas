package dev.opencivitas.shop;

import java.time.Instant;
import java.util.UUID;

public record ChestShop(
        long id,
        String worldName,
        int signX,
        int signY,
        int signZ,
        int containerX,
        int containerY,
        int containerZ,
        ShopOwnerType ownerType,
        UUID ownerId,
        Long businessId,
        String accountName,
        String businessSlug,
        String itemKey,
        int quantity,
        Long buyPriceCents,
        Long sellPriceCents,
        boolean active,
        Instant createdAt
) {
}
