package dev.opencivitas.navigation;

public record SavedLocation(
        String id,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
