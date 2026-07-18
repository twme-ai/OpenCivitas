package dev.opencivitas.election;

import java.math.BigDecimal;
import java.util.Map;

public record ElectionRound(
        int number,
        Map<String, BigDecimal> tallies,
        String elected,
        String eliminated
) {
    public ElectionRound {
        tallies = Map.copyOf(tallies);
    }
}
