package dev.opencivitas.election;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ElectionCount(
        List<String> winners,
        List<String> ranking,
        List<ElectionRound> rounds,
        Map<String, BigDecimal> finalTallies
) {
    public ElectionCount {
        winners = List.copyOf(winners);
        ranking = List.copyOf(ranking);
        rounds = List.copyOf(rounds);
        finalTallies = Map.copyOf(finalTallies);
    }
}
