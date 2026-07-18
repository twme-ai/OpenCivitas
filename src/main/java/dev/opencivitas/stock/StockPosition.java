package dev.opencivitas.stock;

public record StockPosition(
        String symbol,
        String issuerBusiness,
        long availableQuantity,
        long saleEscrowQuantity,
        long lastPriceCents
) {
    public long totalQuantity() {
        return Math.addExact(availableQuantity, saleEscrowQuantity);
    }
}
