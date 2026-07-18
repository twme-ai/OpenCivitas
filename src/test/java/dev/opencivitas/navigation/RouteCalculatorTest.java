package dev.opencivitas.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteCalculatorTest {
    @Test
    void cardinalAndDiagonalBearingsMatchMinecraftCoordinates() {
        assertEquals("north", RouteCalculator.route(0, 0, 0, -10).direction());
        assertEquals("north-east", RouteCalculator.route(0, 0, 10, -10).direction());
        assertEquals("east", RouteCalculator.route(0, 0, 10, 0).direction());
        assertEquals("south-east", RouteCalculator.route(0, 0, 10, 10).direction());
        assertEquals("south", RouteCalculator.route(0, 0, 0, 10).direction());
        assertEquals("south-west", RouteCalculator.route(0, 0, -10, 10).direction());
        assertEquals("west", RouteCalculator.route(0, 0, -10, 0).direction());
        assertEquals("north-west", RouteCalculator.route(0, 0, -10, -10).direction());
        assertEquals(10, RouteCalculator.route(0, 0, 6, 8).distance());
    }
}
