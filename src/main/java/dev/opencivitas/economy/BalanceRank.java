package dev.opencivitas.economy;

import java.util.UUID;

public record BalanceRank(UUID playerId, String playerName, long balanceCents) {
}
