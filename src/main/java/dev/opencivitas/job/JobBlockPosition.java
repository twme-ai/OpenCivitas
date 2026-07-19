package dev.opencivitas.job;

public record JobBlockPosition(String worldName, int x, int y, int z) {
    public JobBlockPosition {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("A block world is required");
        }
    }
}
