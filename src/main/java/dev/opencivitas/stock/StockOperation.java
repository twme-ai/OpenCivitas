package dev.opencivitas.stock;

public record StockOperation<T>(StockResult result, T value) {
    public static <T> StockOperation<T> success(T value) {
        return new StockOperation<>(StockResult.SUCCESS, value);
    }

    public static <T> StockOperation<T> result(StockResult result) {
        return new StockOperation<>(result, null);
    }
}
