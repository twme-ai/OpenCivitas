package dev.opencivitas.shop;

public record ShopSettlement(
        ShopResult result,
        long saleId,
        int itemAmount,
        long totalCents,
        long customerBalanceCents,
        long ownerBalanceCents
) {
    public static ShopSettlement failed(ShopResult result) {
        return new ShopSettlement(result, 0, 0, 0, 0, 0);
    }
}
