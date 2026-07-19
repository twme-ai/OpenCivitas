package dev.opencivitas.job;

public record JobBlockMove(
        JobBlockPosition source,
        JobBlockPosition destination,
        String materialKey
) {
    public JobBlockMove {
        if (source == null || destination == null || materialKey == null || materialKey.isBlank()) {
            throw new IllegalArgumentException("A complete block move is required");
        }
    }
}
