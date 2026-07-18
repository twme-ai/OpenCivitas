package dev.opencivitas.protection;

public record ProtectionSource(ProtectionSourceType type, String identifier) {
    public ProtectionSource {
        if (type == null || identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Protection source type and identifier are required");
        }
    }
}
