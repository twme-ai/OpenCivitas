package dev.opencivitas.stock;

import java.util.UUID;

public record StockShareholder(UUID playerId, String playerName, long quantity) { }
