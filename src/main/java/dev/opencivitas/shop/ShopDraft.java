package dev.opencivitas.shop;

import java.util.UUID;

public record ShopDraft(
        String worldName,
        int signX,
        int signY,
        int signZ,
        int containerX,
        int containerY,
        int containerZ,
        ShopOwnerType ownerType,
        UUID ownerId,
        String businessSlug,
        String itemKey,
        int quantity,
        Long buyPriceCents,
        Long sellPriceCents
) {
}
