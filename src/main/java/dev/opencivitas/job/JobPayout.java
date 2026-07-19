package dev.opencivitas.job;

import java.util.UUID;

public record JobPayout(
        UUID playerId,
        long amountCents,
        int actionCount,
        long balanceCents
) {
}
