package dev.opencivitas.protection;

public record ProtectionKey(String world, int x, int y, int z) {
    public ProtectionKey {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
    }
}
