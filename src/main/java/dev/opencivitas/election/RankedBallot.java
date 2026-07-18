package dev.opencivitas.election;

import java.util.List;

public record RankedBallot(List<String> preferences) {
    public RankedBallot {
        preferences = List.copyOf(preferences);
    }
}
