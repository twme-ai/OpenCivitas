package dev.opencivitas.election;

import java.util.List;

public record ElectionDetails(
        Election election,
        List<ElectionChoice> choices,
        int ballotCount,
        List<ElectionResultEntry> results
) {
    public ElectionDetails {
        choices = List.copyOf(choices);
        results = List.copyOf(results);
    }
}
