package dev.opencivitas.shop;

public record ParsedShopSign(
        ShopOwnerType ownerType,
        String businessSlug,
        int quantity,
        Long buyPriceCents,
        Long sellPriceCents,
        String itemInput
) {
}
