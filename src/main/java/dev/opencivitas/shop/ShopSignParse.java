package dev.opencivitas.shop;

import java.util.Optional;

public record ShopSignParse(ShopSignStatus status, Optional<ParsedShopSign> sign) {
    public static ShopSignParse failed(ShopSignStatus status) {
        return new ShopSignParse(status, Optional.empty());
    }

    public static ShopSignParse parsed(ParsedShopSign sign) {
        return new ShopSignParse(ShopSignStatus.SUCCESS, Optional.of(sign));
    }
}
