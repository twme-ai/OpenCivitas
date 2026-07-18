package dev.opencivitas.election;

import java.util.Optional;

public record ElectionOperation(
        ElectionActionResult result,
        Optional<Election> election,
        Optional<ElectionCount> count
) {
    public static ElectionOperation result(ElectionActionResult result) {
        return new ElectionOperation(result, Optional.empty(), Optional.empty());
    }

    public static ElectionOperation election(Election election) {
        return new ElectionOperation(ElectionActionResult.SUCCESS, Optional.of(election), Optional.empty());
    }
}
