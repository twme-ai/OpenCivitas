package dev.opencivitas.shop;

import java.util.Optional;

public record ShopCreation(ShopResult result, Optional<ChestShop> shop) {
    public static ShopCreation failed(ShopResult result) {
        return new ShopCreation(result, Optional.empty());
    }

    public static ShopCreation created(ChestShop shop) {
        return new ShopCreation(ShopResult.SUCCESS, Optional.of(shop));
    }
}
