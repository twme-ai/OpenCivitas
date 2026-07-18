package dev.opencivitas.navigation;

public final class RouteCalculator {
    private static final String[] DIRECTIONS = {
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west"
    };

    private RouteCalculator() {
    }

    public static NavigationRoute route(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        int directionIndex = (int) Math.round(angle / 45.0) % DIRECTIONS.length;
        return new NavigationRoute(Math.hypot(dx, dz), DIRECTIONS[directionIndex]);
    }
}
