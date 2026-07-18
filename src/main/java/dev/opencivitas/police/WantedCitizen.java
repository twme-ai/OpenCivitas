package dev.opencivitas.police;

import java.time.Instant;
import java.util.UUID;

public record WantedCitizen(
        UUID playerId,
        String playerName,
        int openCharges,
        long totalFineCents,
        int totalJailMinutes,
        Instant oldestChargeAt
) {
}
