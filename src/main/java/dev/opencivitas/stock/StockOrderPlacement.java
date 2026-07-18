package dev.opencivitas.stock;

import java.util.List;

public record StockOrderPlacement(StockOrder order, List<StockTrade> trades) {
    public StockOrderPlacement {
        trades = List.copyOf(trades);
    }
}
