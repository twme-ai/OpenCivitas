package dev.opencivitas.navigation;

public record NavigationRoute(double distance, String direction) {
    public NavigationRoute {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Invalid distance");
    }
}
